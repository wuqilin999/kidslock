package com.kidslock.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 通用题库：
 *  - 离线：从 assets/questions.json 加载内置题库；
 *  - 在线：从 NAS 后台 /api/next 拉题（后台已完成弱项优先选题），/api/submit 回传进度。
 *
 * 在线任何失败（超时/404/JSON 异常）均返回 null / 静默失败，由调用方回退内置题库，
 * 保证断网时仍可用。所有网络方法均在后台线程执行（Android 6.0+ 禁止主线程网络）。
 */
public class QuestionBank {

    private static final String TAG = "QuestionBank";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private static final List<String> PRIMARY_SUBJECTS = Arrays.asList("语文", "数学", "英语", "科学");
    private static final List<String> JUNIOR_SUBJECTS =
            Arrays.asList("语文", "数学", "英语", "科学", "物理", "历史", "道德与法治");

    public static class Question {
        public String id;
        public String subject;
        public int grade;
        public String edition;
        public String unit;
        public String knowledge;
        public String type;
        public String stem;
        public List<String> options = new ArrayList<>();
        public String answer;
        public String explanation;
    }

    private final List<Question> all = new ArrayList<>();
    private final Random random = new Random();

    // ==================== 离线：assets ====================

    /** 从 assets 加载内置题库（离线优先）。 */
    public static QuestionBank loadFromAssets(Context context) {
        QuestionBank bank = new QuestionBank();
        try {
            InputStream is = context.getAssets().open("questions.json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            bank.parse(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "load assets failed", e);
        }
        return bank;
    }

    private void parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("questions");
        for (int i = 0; i < arr.length(); i++) {
            all.add(parseQuestion(arr.getJSONObject(i)));
        }
    }

    private static Question parseQuestion(JSONObject o) throws Exception {
        Question q = new Question();
        q.id = o.optString("id");
        q.subject = o.optString("subject");
        q.grade = o.optInt("grade");
        q.edition = o.optString("edition");
        q.unit = o.optString("unit");
        q.knowledge = o.optString("knowledge");
        q.type = o.optString("type");
        q.stem = o.optString("stem");
        q.answer = o.optString("answer");
        q.explanation = o.optString("explanation");
        JSONArray opts = o.optJSONArray("options");
        if (opts != null) {
            for (int j = 0; j < opts.length(); j++) q.options.add(opts.getString(j));
        }
        return q;
    }

    // ==================== 在线：NAS 后台 ====================

    /**
     * 从后台拉取一轮题目（后台已按弱项优先选好）。成功返回题目列表，失败返回 null。
     * 必须在后台线程调用。
     */
    public static List<Question> fetchRoundFromServer(String baseUrl, String childId,
                                                      int grade, String edition, int count) {
        HttpURLConnection conn = null;
        try {
            String url = normalizeBase(baseUrl) + "/api/next?child_id=" + enc(childId)
                    + "&count=" + count + "&grade=" + grade + "&edition=" + enc(edition);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "fetchRound HTTP " + code);
                return null;
            }
            String body = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.getJSONArray("questions");
            List<Question> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) out.add(parseQuestion(arr.getJSONObject(i)));
            return out;
        } catch (Exception e) {
            Log.w(TAG, "fetchRoundFromServer failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 回传答题结果到后台（更新掌握度）。resultsJson 形如 [{"knowledge":..,"correct":true}]。
     * 静默失败，不影响本地解锁。必须在后台线程调用。
     */
    public static void submitResults(String baseUrl, String childId, String resultsJson) {
        HttpURLConnection conn = null;
        try {
            String url = normalizeBase(baseUrl) + "/api/submit";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "child_id=" + enc(childId) + "&results=" + enc(resultsJson);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "submitResults failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String normalizeBase(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 选题（离线用） ====================

    /** 年级 → 科目集（小学语数英；初中主科）。 */
    public List<String> gradeSubjects(int grade) {
        return grade <= 6 ? new ArrayList<>(PRIMARY_SUBJECTS) : new ArrayList<>(JUNIOR_SUBJECTS);
    }

    private List<Question> filter(int grade, List<String> subjects) {
        List<Question> out = new ArrayList<>();
        for (Question q : all) {
            if (q.grade != grade) continue;
            if (subjects != null && !subjects.contains(q.subject)) continue;
            out.add(q);
        }
        return out;
    }

    /**
     * 抽取一轮题目（默认 requiredCount 道），弱项优先。
     * masteryJson: { "知识点": 掌握度0~1 }
     */
    public List<Question> getRound(int grade, List<String> subjects, int count, String masteryJson) {
        List<Question> pool = filter(grade, subjects);
        if (pool.isEmpty()) return new ArrayList<>();
        JSONObject mastery = new JSONObject();
        try {
            if (masteryJson != null) mastery = new JSONObject(masteryJson);
        } catch (Exception ignore) {
        }

        List<Question> picked = new ArrayList<>();
        List<Question> working = new ArrayList<>(pool);
        for (int n = 0; n < count && !working.isEmpty(); n++) {
            double totalW = 0;
            double[] weights = new double[working.size()];
            for (int i = 0; i < working.size(); i++) {
                Question q = working.get(i);
                double m = mastery.optDouble(q.knowledge, 0.5);
                double w = Math.max(0.05, 1.0 - m); // 掌握度越低权重越高
                weights[i] = w;
                totalW += w;
            }
            double r = random.nextDouble() * totalW;
            int idx = working.size() - 1;
            for (int i = 0; i < weights.length; i++) {
                r -= weights[i];
                if (r <= 0) {
                    idx = i;
                    break;
                }
            }
            Question chosen = working.remove(idx);
            Collections.shuffle(chosen.options, random);
            picked.add(chosen);
        }
        return picked;
    }

    public int size() {
        return all.size();
    }
}
