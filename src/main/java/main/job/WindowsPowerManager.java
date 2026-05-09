package main.job;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsPowerManager {

    private static Process sleepPreventProcess;

    // 봇 실행 중 절전 모드 차단
    // PowerShell로 SetThreadExecutionState 호출 후 Java 프로세스 감시
    // Java 프로세스가 죽으면 자동으로 절전 허용으로 복원
    public static void preventSleep() {
        try {
            long javaPid = ProcessHandle.current().pid();
            String psScript = String.format(
                "$sig = '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags);';" +
                "$t = Add-Type -MemberDefinition $sig -Name 'SleepHelper' -Namespace 'Win32' -PassThru -ErrorAction SilentlyContinue;" +
                "if (!$t) { $t = [Win32.SleepHelper] };" +
                "$t::SetThreadExecutionState(0x80000041);" +  // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_AWAYMODE_REQUIRED
                "while (Get-Process -Id %d -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 5 };" +
                "$t::SetThreadExecutionState(0x80000000);",  // ES_CONTINUOUS (release)
                javaPid
            );

            sleepPreventProcess = new ProcessBuilder("powershell", "-NonInteractive", "-Command", psScript)
                .redirectErrorStream(true)
                .start();
            log.info("절전 모드 차단 활성화 (Java PID: {})", javaPid);
        } catch (Exception e) {
            log.warn("절전 모드 차단 실패: {}", e.getMessage());
        }
    }

    public static void allowSleep() {
        if (sleepPreventProcess != null && sleepPreventProcess.isAlive()) {
            sleepPreventProcess.destroy();
            sleepPreventProcess = null;
            log.info("절전 모드 차단 해제");
        }
    }
}