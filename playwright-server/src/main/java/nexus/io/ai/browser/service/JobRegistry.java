package nexus.io.ai.browser.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.jfinal.kit.Kv;

import lombok.extern.slf4j.Slf4j;
import nexus.io.model.body.RespBodyVo;

/**
 * 长任务注册表:让「跑很久的批次」不再把客户端拖到超时
 *
 * <p>
 * <b>为什么需要它</b>:一个 {@code commands} 批次里连做十几个动作、每个动作后面还跟着等待,跑
 * 60~90 秒是常态。HTTP 客户端(尤其是 PowerShell 的 {@code Invoke-WebRequest})很容易在这时候超时,
 * 而超时之后**批次其实还在服务端继续跑**——调用方既不知道进度,也不敢重发(重发就是重复执行)。
 *
 * <p>
 * 用法:批次参数里加 {@code "async": true},接口立刻返回 {@code {jobId, status:"running"}},之后用
 * {@code get_job} 轮询结果、用 {@code cancel_job} 取消(取消是**协作式**的:批次会在每一步之间检查
 * 取消标记,已经发出去的那一步不会被打断,避免把页面停在半路)。
 *
 * <p>
 * 任务只保留最近 {@value #MAX_JOBS} 个,结果保存在内存里(服务重启即丢),因此它是「一次调用的
 * 观测窗口」,不是持久化队列。
 */
@Slf4j
public final class JobRegistry {

  /** 最多保留多少个任务(超出时丢掉最老的已结束任务) */
  private static final int MAX_JOBS = 50;

  /** 同时最多跑几个长任务:浏览器操作本来就该串行,给 2 个足够「一个跑、一个等」 */
  private static final int MAX_CONCURRENCY = 2;

  /** 单个任务的运行状态 */
  public static final class Job {
    public final String id;
    public final String method;
    public final long browserId;
    public final long startedAt;
    public volatile long finishedAt;
    /** running / done / failed / cancelled */
    public volatile String status = "running";
    public volatile RespBodyVo result;
    public volatile String error;
    public volatile boolean cancelRequested;
    /** 已经跑完几步(批次用,便于看进度) */
    public volatile int steps;

    Job(String id, String method, long browserId) {
      this.id = id;
      this.method = method;
      this.browserId = browserId;
      this.startedAt = System.currentTimeMillis();
    }

    public Kv describe(boolean withResult) {
      Kv kv = Kv.by("jobId", id).set("method", method).set("browserId", browserId).set("status", status)
          .set("startedAt", startedAt).set("steps", steps).set("cancelRequested", cancelRequested);
      if (finishedAt > 0) {
        kv.set("finishedAt", finishedAt).set("durationMs", finishedAt - startedAt);
      }
      if (error != null) {
        kv.set("error", error);
      }
      if (withResult && result != null) {
        kv.set("ok", result.isOk()).set("msg", result.getMsg()).set("data", result.getData());
      }
      return kv;
    }
  }

  private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
  private static final AtomicLong SEQ = new AtomicLong();

  private static final ExecutorService POOL = Executors.newFixedThreadPool(MAX_CONCURRENCY, new ThreadFactory() {
    private final AtomicLong seq = new AtomicLong();

    @Override
    public Thread newThread(Runnable runnable) {
      Thread thread = new Thread(runnable, "browser-job-" + seq.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    }
  });

  private JobRegistry() {
  }

  /**
   * 提交一个长任务,立刻返回任务 ID
   *
   * @param work 真正要干的活;抛异常会被记成 failed,不会把线程池打挂
   */
  public static Job submit(String method, long browserId, java.util.function.Consumer<Job> work) {
    String id = "job-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
    Job job = new Job(id, method, browserId);
    JOBS.put(id, job);
    trim();
    POOL.execute(() -> {
      try {
        work.accept(job);
        if (job.cancelRequested && job.result == null) {
          job.status = "cancelled";
        } else if (job.result != null && job.result.isOk()) {
          job.status = "done";
        } else if (job.result != null) {
          job.status = "failed";
          job.error = job.result.getMsg();
        } else {
          job.status = "done";
        }
      } catch (Throwable t) {
        job.status = "failed";
        job.error = PlaywrightService.briefMessage(String.valueOf(t.getMessage()));
        log.warn("长任务 {} 执行异常:{}", id, job.error);
      } finally {
        job.finishedAt = System.currentTimeMillis();
      }
    });
    return job;
  }

  public static Job get(String jobId) {
    return jobId == null ? null : JOBS.get(jobId);
  }

  /** 请求取消:批次会在下一步之前停下来(当前这一步不打断) */
  public static boolean cancel(String jobId) {
    Job job = get(jobId);
    if (job == null) {
      return false;
    }
    job.cancelRequested = true;
    return true;
  }

  /** 这个任务是不是已经被要求取消(批次循环每步问一次) */
  public static boolean cancelRequested(String jobId) {
    Job job = get(jobId);
    return job != null && job.cancelRequested;
  }

  /** 最近的任务(新的在前) */
  public static List<Job> recent(int limit) {
    List<Job> jobs = new ArrayList<>(JOBS.values());
    jobs.sort(Comparator.comparingLong((Job job) -> job.startedAt).reversed());
    return limit > 0 && jobs.size() > limit ? new ArrayList<>(jobs.subList(0, limit)) : jobs;
  }

  /** 超出上限时,从最老的**已结束**任务开始丢 */
  private static void trim() {
    if (JOBS.size() <= MAX_JOBS) {
      return;
    }
    List<Job> finished = new ArrayList<>();
    for (Job job : JOBS.values()) {
      if (!"running".equals(job.status)) {
        finished.add(job);
      }
    }
    finished.sort(Comparator.comparingLong(job -> job.startedAt));
    for (Job job : finished) {
      if (JOBS.size() <= MAX_JOBS) {
        break;
      }
      JOBS.remove(job.id);
    }
  }

  /** 供测试重置状态 */
  static void clear() {
    JOBS.clear();
  }

  /** 把「检查取消 + 记录进度」包成一个可传进批次的回调 */
  public static Supplier<Boolean> cancelFlag(Job job) {
    return () -> job != null && job.cancelRequested;
  }
}
