/**
 * Upload pacing for the data room (#142). Dropping a folder used to start every file at once; each upload is
 * buffered on the backend until it ends, so unbounded parallel uploads could fill the pod's temporary storage.
 * Files now go at most {@link UPLOAD_CONCURRENCY} at a time per browser tab, and an upload the backend refuses
 * with 503 (it is at its own limit) is retried after the `Retry-After` it gives.
 */
export const UPLOAD_CONCURRENCY = 3;
export const MAX_BUSY_RETRIES = 6;

/** Runs at most `max` tasks at the same time, in the order they were queued. */
export function createLimiter(max: number) {
  let active = 0;
  const waiting: Array<() => void> = [];

  const next = () => {
    if (active >= max) return;
    const start = waiting.shift();
    if (start) {
      active++;
      start();
    }
  };

  return function run<T>(task: () => Promise<T>): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      waiting.push(() => {
        task()
          .then(resolve, reject)
          .finally(() => {
            active--;
            next();
          });
      });
      next();
    });
  };
}

/** Delay before retrying a refused upload: the server's Retry-After (seconds), else a growing default. */
export function retryDelayMs(retryAfter: string | null, attempt: number): number {
  const seconds = Number(retryAfter);
  if (Number.isFinite(seconds) && seconds > 0) {
    return Math.min(seconds, 60) * 1000;
  }
  return Math.min(2 ** attempt, 30) * 1000;
}

/** One limiter per tab, shared by every upload area. */
export const runUpload = createLimiter(UPLOAD_CONCURRENCY);
