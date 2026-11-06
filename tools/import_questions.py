#!/usr/bin/env python3
"""Convert ChinaTextbookStudyFree quiz JSON into KidsLock's asset format."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any


GRADE_RE = re.compile(r"(?P<grade>[一二三四五六七八九十]|[1-9])年级")
GRADE_DIGITS = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
    "七": 7,
    "八": 8,
    "九": 9,
}

SUBJECTS = {
    "chinese": "语文",
    "math": "数学",
    "english": "英语",
    "science": "科学",
}

TRUE_VALUES = {"对", "正确", "true", "t", "√", "yes", "是"}
FALSE_VALUES = {"错", "错误", "false", "f", "×", "no", "否"}


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def canonical_text(value: Any) -> str:
    return clean_text(value).replace(" ", "")


def grade_from_filename(filename: str) -> int | None:
    matches = list(GRADE_RE.finditer(filename))
    if not matches:
        return None
    value = matches[-1].group("grade")
    return int(value) if value.isdigit() else GRADE_DIGITS.get(value)


def normalize_question(question: dict[str, Any]) -> tuple[str, list[str], str] | None:
    """Return a UI-compatible stem/options/answer tuple, or reject the item."""
    stem = clean_text(question.get("question"))
    kind = clean_text(question.get("type")).lower()
    if not stem:
        return None

    if kind == "true_false":
        raw_answer = canonical_text(question.get("answer")).lower()
        if raw_answer in {canonical_text(v).lower() for v in TRUE_VALUES}:
            return stem, ["对", "错"], "对"
        if raw_answer in {canonical_text(v).lower() for v in FALSE_VALUES}:
            return stem, ["对", "错"], "错"
        return None

    if kind != "choice":
        return None
    raw_options = question.get("options")
    if not isinstance(raw_options, list):
        return None
    options = [clean_text(item) for item in raw_options]
    if not 2 <= len(options) <= 4 or any(not item for item in options):
        return None
    if len({canonical_text(item) for item in options}) != len(options):
        return None
    answer_key = canonical_text(question.get("answer"))
    if not answer_key:
        return None
    matching = [item for item in options if canonical_text(item) == answer_key]
    if len(matching) != 1:
        return None
    # Preserve the exact option spelling so the Android UI's string comparison succeeds.
    return stem, options, matching[0]


def edition_from_textbook(textbook: Any) -> str:
    return clean_text(textbook) or "ChinaTextbookStudyFree"


def make_id(path: str, section: str, question: dict[str, Any], index: int) -> str:
    source = f"{path}|{section}|{question.get('id', index)}"
    digest = hashlib.sha1(source.encode("utf-8")).hexdigest()[:12]
    return f"ctsf_{digest}"


def convert_source(
    source_zip: Path,
    existing: list[dict[str, Any]] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    questions = list(existing or [])
    seen = {
        (
            int(item.get("grade", 0)),
            clean_text(item.get("subject")),
            canonical_text(item.get("stem")),
            tuple(canonical_text(option) for option in item.get("options", [])),
            canonical_text(item.get("answer")),
        )
        for item in questions
    }
    stats: Counter[str] = Counter()
    imported_grades: Counter[str] = Counter()
    imported_subjects: Counter[str] = Counter()
    source_files = 0
    existing_count = len(questions)

    with zipfile.ZipFile(source_zip) as archive:
        for member in sorted(archive.namelist()):
            match = re.search(r"/output/(chinese|math|english|science)/quizzes/([^/]+\.json)$", member)
            if not match:
                continue
            try:
                document = json.loads(archive.read(member).decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                stats["invalid_json"] += 1
                continue
            grade = grade_from_filename(match.group(2))
            if grade is None or not 1 <= grade <= 9:
                stats["missing_or_out_of_range_grade"] += 1
                continue
            source_files += 1
            subject = SUBJECTS[match.group(1)]
            textbook = edition_from_textbook(document.get("textbook"))
            unit = clean_text(document.get("unit")) or "未分类单元"
            for section in ("unit_test", "exam"):
                section_data = document.get(section)
                raw_questions = section_data.get("questions", []) if isinstance(section_data, dict) else []
                for index, raw_question in enumerate(raw_questions, start=1):
                    if not isinstance(raw_question, dict):
                        stats["invalid_question"] += 1
                        continue
                    normalized = normalize_question(raw_question)
                    if normalized is None:
                        stats[f"rejected_{clean_text(raw_question.get('type')) or 'unknown'}"] += 1
                        continue
                    stem, options, answer = normalized
                    fingerprint = (
                        grade,
                        subject,
                        canonical_text(stem),
                        tuple(canonical_text(option) for option in options),
                        canonical_text(answer),
                    )
                    if fingerprint in seen:
                        stats["duplicate"] += 1
                        continue
                    seen.add(fingerprint)
                    questions.append(
                        {
                            "id": make_id(member, section, raw_question, index),
                            "subject": subject,
                            "grade": grade,
                            "edition": textbook,
                            "unit": unit,
                            "knowledge": clean_text(raw_question.get("knowledge_point")) or "未分类知识点",
                            "type": "choice",
                            "difficulty": raw_question.get("difficulty", 1),
                            "stem": stem,
                            "options": options,
                            "answer": answer,
                            "explanation": clean_text(raw_question.get("explanation")),
                        }
                    )
                    stats[f"imported_{section}"] += 1
                    stats[f"imported_{'true_false' if clean_text(raw_question.get('type')).lower() == 'true_false' else 'choice'}"] += 1
                    imported_grades[str(grade)] += 1
                    imported_subjects[subject] += 1

    report = {
        "source_files": source_files,
        "existing_questions": existing_count,
        "imported_questions": len(questions) - existing_count,
        "total_questions": len(questions),
        "imported_by_grade": dict(sorted(imported_grades.items(), key=lambda pair: int(pair[0]))),
        "imported_by_subject": dict(sorted(imported_subjects.items())),
        "stats": dict(sorted(stats.items())),
    }
    return questions, report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source_zip", type=Path)
    parser.add_argument("output_json", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    existing_data = json.loads(args.output_json.read_text(encoding="utf-8"))
    questions, report = convert_source(args.source_zip, existing_data.get("questions", []))
    output = dict(existing_data)
    output["questions"] = questions
    output.setdefault("meta", {})["import_note"] = (
        "已导入 ChinaTextbookStudyFree 中答案可与选项唯一匹配的选择题；"
        "判断题统一转换为“对/错”二选一。未通过校验的题目未导入。"
    )
    args.output_json.write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
