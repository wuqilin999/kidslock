import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "import_questions.py"
spec = importlib.util.spec_from_file_location("import_questions", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ImportQuestionsTest(unittest.TestCase):
    def test_choice_requires_one_exact_answer_and_unique_options(self):
        self.assertEqual(
            module.normalize_question({"type": "choice", "question": "题", "options": ["甲", "乙"], "answer": "乙"}),
            ("题", ["甲", "乙"], "乙"),
        )
        for options, answer in [
            (["甲", "乙"], "丙"),
            (["甲", "甲"], "甲"),
            (["甲", ""], "甲"),
            (["甲", "乙", "丙", "丁", "戊"], "甲"),
        ]:
            with self.subTest(options=options, answer=answer):
                self.assertIsNone(module.normalize_question({
                    "type": "choice", "question": "题", "options": options, "answer": answer,
                }))

    def test_true_false_only_accepts_unambiguous_values(self):
        for source, expected in [("正确", "对"), ("True", "对"), ("×", "错"), ("错误", "错")]:
            with self.subTest(source=source):
                self.assertEqual(
                    module.normalize_question({"type": "true_false", "question": "题", "answer": source}),
                    ("题", ["对", "错"], expected),
                )
        self.assertIsNone(module.normalize_question({
            "type": "true_false", "question": "题", "answer": "不确定",
        }))

    def test_grade_comes_from_book_name_not_starting_grade(self):
        self.assertEqual(module.grade_from_filename("英语（三年级起点）五年级上册_unit1.json"), 5)
        self.assertEqual(module.grade_from_filename("数学（六年级下册）_unit1.json"), 6)
        self.assertIsNone(module.grade_from_filename("没有年级_unit1.json"))

    def test_unsupported_and_missing_stems_are_rejected(self):
        self.assertIsNone(module.normalize_question({
            "type": "calculation", "question": "题", "options": ["1", "2"], "answer": "1",
        }))
        self.assertIsNone(module.normalize_question({
            "type": "choice", "question": " ", "options": ["1", "2"], "answer": "1",
        }))


if __name__ == "__main__":
    unittest.main()
