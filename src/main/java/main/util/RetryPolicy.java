package main.util;

import lombok.extern.slf4j.Slf4j;

import java.util.function.BooleanSupplier;

@Slf4j
public final class RetryPolicy {

  private RetryPolicy() {}

  /**
   * Retries a boolean-returning operation up to {@code maxAttempts} times.
   * Sleeps {@code delayMs} milliseconds between attempts.
   *
   * @param operation   supplier that returns true on success
   * @param maxAttempts maximum number of attempts (including the first)
   * @param delayMs     delay in milliseconds between attempts
   * @param label       log label shown in warn messages on each failure
   * @return true if any attempt succeeded, false if all attempts failed
   */
  public static boolean withRetry(
      BooleanSupplier operation, int maxAttempts, long delayMs, String label) {
    for (int i = 0; i < maxAttempts; i++) {
      if (operation.getAsBoolean()) return true;
      if (i < maxAttempts - 1) {
        log.warn("{} 실패 (시도: {}). {}ms 후 재시도...", label, i + 1, delayMs);
        try {
          Thread.sleep(delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }
}
