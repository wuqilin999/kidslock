package com.kidslock.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 锁屏答题界面（改造版）：
 * - 展示一道题（语文/数学/英语，按年级/教材/进度抽取），4 个选项。
 * - 一轮 requiredCount 道题；任一道答错 → 展示参考答案与解析 → 重新开始本轮。
 * - 一轮全对 → 解锁并进入 interval 分钟自由观看，到点再次锁屏。
 * - 家长 PIN 解锁 → 切换到家长模式（不再弹题）。
 * - 按 HOME/BACK 无法绕过；断电重启仍锁（由 PrefManager + BootReceiver 保证）。
 */
public class LockScreenActivity extends AppCompatActivity {

    private PrefManager pref;
    private QuestionBank bank;

    // 本轮状态
    private List<QuestionBank.Question> round;
    private int roundIndex = 0;
    private int requiredCount;
    private QuestionBank.Question current;
    private List<String> currentOptions;

    // 答题结果（用于回传 NAS 后台更新掌握度）
    private final JSONArray resultsJson = new JSONArray();

    // Views
    private View layoutMain;
    private TextView tvSubject;
    private TextView tvStem;
    private TextView tvProgress;
    private TextView tvFeedback;
    private Button[] optionButtons;

    // PIN
    private View layoutPin;
    private TextView tvPinDisplay;
    private StringBuilder pinInput = new StringBuilder();

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock_screen);

        pref = new PrefManager(this);
        bank = QuestionBank.loadFromAssets(this);
        requiredCount = pref.getUnlockCount();

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        hideSystemUI();

        initViews();
        loadRound();
    }

    private void initViews() {
        layoutMain = findViewById(R.id.layoutMain);
        tvSubject = findViewById(R.id.tvSubject);
        tvStem = findViewById(R.id.tvStem);
        tvProgress = findViewById(R.id.tvProgress);
        tvFeedback = findViewById(R.id.tvFeedback);

        optionButtons = new Button[]{
                findViewById(R.id.btnOption1),
                findViewById(R.id.btnOption2),
                findViewById(R.id.btnOption3),
                findViewById(R.id.btnOption4)
        };
        for (int i = 0; i < optionButtons.length; i++) {
            final int idx = i;
            optionButtons[i].setOnClickListener(v -> onOptionSelected(idx));
        }

        findViewById(R.id.btnParentUnlock).setOnClickListener(v -> showPinPad());

        // PIN 界面
        layoutPin = findViewById(R.id.layoutPin);
        tvPinDisplay = findViewById(R.id.tvPinDisplay);

        int[] pinButtonIds = {
                R.id.btnPin0, R.id.btnPin1, R.id.btnPin2, R.id.btnPin3,
                R.id.btnPin4, R.id.btnPin5, R.id.btnPin6, R.id.btnPin7,
                R.id.btnPin8, R.id.btnPin9
        };
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            findViewById(pinButtonIds[i]).setOnClickListener(v -> onPinDigit(digit));
        }
        findViewById(R.id.btnPinClear).setOnClickListener(v -> {
            pinInput.setLength(0);
            updatePinDisplay();
        });
        findViewById(R.id.btnPinBack).setOnClickListener(v -> {
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        });
    }

    // ==================== 出题 ====================

    private void loadRound() {
        // 在线模式：后台线程拉题（Android 6+ 禁止主线程网络）；失败自动回退内置题库
        if (pref.isOnline() && !pref.getServerUrl().isEmpty() && !pref.getChildId().isEmpty()) {
            final String serverUrl = pref.getServerUrl();
            final String childId = pref.getChildId();
            final String edition = pref.getEdition();
            final int grade = pref.getGrade();
            tvStem.setText("正在加载题目…");
            tvProgress.setText("");
            for (Button b : optionButtons) b.setVisibility(View.GONE);
            new Thread(() -> {
                final List<QuestionBank.Question> remote =
                        QuestionBank.fetchRoundFromServer(serverUrl, childId, grade, edition, requiredCount);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (remote != null && !remote.isEmpty()) {
                        round = remote;
                        roundIndex = 0;
                        loadCurrent();
                    } else {
                        loadLocalRound(); // 网络失败 → 回退内置题库，保证离线可用
                    }
                });
            }).start();
        } else {
            loadLocalRound();
        }
    }

    /** 内置题库（离线）出题。 */
    private void loadLocalRound() {
        int grade = pref.getGrade();
        List<String> subjects = bank.gradeSubjects(grade);
        round = bank.getRound(grade, subjects, requiredCount, pref.getMasteryJson());
        if (round.isEmpty()) {
            // 题库为空（不该发生）：避免把孩子锁死，提示家长
            tvStem.setText("暂无题目，请联系家长添加题库。");
            tvProgress.setText("");
            for (Button b : optionButtons) b.setVisibility(View.GONE);
            return;
        }
        roundIndex = 0;
        loadCurrent();
    }

    private void loadCurrent() {
        current = round.get(roundIndex);
        currentOptions = current.options;
        tvSubject.setText(current.subject + " · " + current.unit);
        tvStem.setText(current.stem);
        // 题干较长（如英语）时缩小字号，避免溢出
        tvStem.setTextSize(current.stem.length() > 16 ? 28 : 40);

        // 每题选项数量可能不同，隐藏上一题留下的多余按钮，避免二选一误显示旧选项。
        for (int i = 0; i < optionButtons.length; i++) {
            Button button = optionButtons[i];
            if (i < currentOptions.size()) {
                button.setVisibility(View.VISIBLE);
                button.setText(currentOptions.get(i));
                button.setBackgroundResource(R.drawable.btn_option_bg);
                button.setEnabled(true);
            } else {
                button.setVisibility(View.GONE);
                button.setText("");
                button.setEnabled(false);
            }
        }
        updateProgress();
        tvFeedback.setVisibility(View.GONE);
    }

    private void onOptionSelected(int index) {
        if (current == null || index >= currentOptions.size()) return;
        for (Button b : optionButtons) b.setEnabled(false);

        String selected = currentOptions.get(index);
        boolean correct = selected.equals(current.answer);
        recordResult(current.knowledge, correct);

        if (correct) {
            pref.recordAnswer(current.knowledge, true);
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_correct);
            tvFeedback.setText("答对啦！");
            tvFeedback.setTextColor(getResources().getColor(R.color.correct_green));
            tvFeedback.setVisibility(View.VISIBLE);

            roundIndex++;
            if (roundIndex >= round.size()) {
                handler.postDelayed(this::unlockChild, 700); // 本轮全对 → 解锁
            } else {
                handler.postDelayed(this::loadCurrent, 900);
            }
        } else {
            pref.recordAnswer(current.knowledge, false);
            optionButtons[index].setBackgroundResource(R.drawable.btn_option_wrong);
            for (int i = 0; i < currentOptions.size(); i++) {
                if (currentOptions.get(i).equals(current.answer)) {
                    optionButtons[i].setBackgroundResource(R.drawable.btn_option_correct);
                    break;
                }
            }
            // 反馈 + 参考答案/解析
            String fb = "答错了。正确答案：" + current.answer + "。";
            if (current.explanation != null && !current.explanation.isEmpty()) {
                fb += " " + current.explanation;
            }
            tvFeedback.setText(fb);
            tvFeedback.setTextColor(getResources().getColor(R.color.wrong_red));
            tvFeedback.setVisibility(View.VISIBLE);
            // 重新开始本轮
            handler.postDelayed(this::loadRound, 2600);
        }
    }

    private void updateProgress() {
        int total = round != null ? round.size() : requiredCount;
        tvProgress.setText("第 " + (roundIndex + 1) + "/" + total + " 题 · 本轮全部答对即可解锁");
    }

    /** 记录一次答题结果，用于本轮结束后回传后台。 */
    private void recordResult(String knowledge, boolean correct) {
        try {
            JSONObject r = new JSONObject();
            r.put("knowledge", knowledge == null ? "" : knowledge);
            r.put("correct", correct);
            resultsJson.put(r);
        } catch (Exception ignore) {
        }
    }

    // ==================== 解锁 ====================

    /** 孩子模式：本轮全对后解锁，进入 interval 分钟自由观看，到点再锁。 */
    private void unlockChild() {
        pref.setLocked(false);
        pref.setHomeAliasEnabled(this, false);
        pref.resetPinFailures();
        submitIfOnline();
        pref.startTimer(pref.getIntervalMinutes()); // 启动自由窗口计时
        LockService.requestFreeWindow(this);        // 隐藏悬浮窗，继续守护（到点再锁）
        finishAffinity();
    }

    /** 在线模式：把本轮答题结果回传后台更新掌握度（后台线程，静默失败）。 */
    private void submitIfOnline() {
        if (!pref.isOnline() || pref.getServerUrl().isEmpty() || pref.getChildId().isEmpty()) return;
        if (resultsJson.length() == 0) return;
        final String serverUrl = pref.getServerUrl();
        final String childId = pref.getChildId();
        final String payload = resultsJson.toString();
        new Thread(() -> QuestionBank.submitResults(serverUrl, childId, payload)).start();
    }

    /** 家长 PIN 解锁：临时放行（保持孩子模式，进入自由窗口，到点再锁）。 */
    private void unlockParent() {
        unlockChild();
    }

    // ==================== 家长PIN ====================

    private void showPinPad() {
        layoutMain.setVisibility(View.GONE);
        layoutPin.setVisibility(View.VISIBLE);
        pinInput.setLength(0);
        updatePinDisplay();
    }

    private void onPinDigit(int digit) {
        if (pinInput.length() < 4) {
            pinInput.append(digit);
            updatePinDisplay();
            if (pinInput.length() == 4) {
                handler.postDelayed(this::checkPin, 200);
            }
        }
    }

    private void updatePinDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(i < pinInput.length() ? "●" : "○");
            if (i < 3) sb.append("  ");
        }
        tvPinDisplay.setText(sb.toString());
    }

    private void checkPin() {
        if (pref.isPinLocked()) {
            long remainingSec = pref.getPinLockRemainingMillis() / 1000;
            Toast.makeText(this, "尝试次数过多，请 " + remainingSec + " 秒后再试", Toast.LENGTH_SHORT).show();
            pinInput.setLength(0);
            updatePinDisplay();
            return;
        }
        if (pinInput.toString().equals(pref.getParentPin())) {
            pref.resetPinFailures();
            // PIN 仅临时放行：保持孩子模式，进入自由窗口，到点再次锁屏。
            // 永久"不弹题"请在设置中切换到家长模式。
            unlockChild();
        } else {
            pref.recordPinFailure();
            Toast.makeText(this, "PIN码错误", Toast.LENGTH_SHORT).show();
            pinInput.setLength(0);
            updatePinDisplay();
        }
    }

    // ==================== 防绕过 ====================

    private static final long RELOCK_DELAY_MS = 800;

    private final Runnable reLockRunnable = new Runnable() {
        @Override
        public void run() {
            if (pref.isLocked()) {
                Intent lockIntent = new Intent(LockScreenActivity.this, LockScreenActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lockIntent);
            }
        }
    };

    @Override
    public void onBackPressed() {
        if (layoutPin.getVisibility() == View.VISIBLE) {
            layoutPin.setVisibility(View.GONE);
            layoutMain.setVisibility(View.VISIBLE);
            pinInput.setLength(0);
            updatePinDisplay();
        }
        // 锁屏主界面按返回不退出
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pref.isLocked()) {
            handler.postDelayed(reLockRunnable, RELOCK_DELAY_MS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(reLockRunnable);
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
    }
}
