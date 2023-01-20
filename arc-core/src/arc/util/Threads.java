package arc.util;

import arc.*;
import arc.func.*;
import arc.struct.*;

import java.lang.ref.*;
import java.util.concurrent.*;

/**
 * Utilities for threaded programming.
 */
public class Threads{

    public static <T> ThreadLocal<T> local(Prov<T> prov){
        return new ThreadLocal<T>(){
            @Override
            protected T initialValue(){
                return prov.get();
            }
        };
    }

    public static <T> T await(Future<T> future){
        try{
            return future.get();
        }catch(ExecutionException | InterruptedException ex){
            throw new ArcRuntimeException(ex.getCause());
        }
    }

    public static void awaitAll(Seq<Future<?>> futures){
        try{
            for(Future<?> f : futures){
                f.get();
            }
        }catch(ExecutionException | InterruptedException ex){
            throw new ArcRuntimeException(ex.getCause());
        }
    }

    /** @return an executor with a fixed number of threads which do not expire
     *  @param threads the number of threads */
    public static ExecutorService executor(@Nullable String name, int threads){
        return Executors.newFixedThreadPool(threads, r -> newThread(r, name, true));
    }

    /** @see #executor(String, int) */
    public static ExecutorService executor(int threads){
        return executor(null, threads);
    }

    /** @see #executor(String, int) */
    public static ExecutorService executor(@Nullable String name){
        return executor(name, OS.cores);
    }

    /** @see #executor(String, int) */
    public static ExecutorService executor(){
        return executor(null);
    }

    /** @return an executor with a max number of threads which expire after 1 minute of inactivity
     *  @param max the maximum number of threads at any given point */
    public static ExecutorService cachedExecutor(@Nullable String name, int max){
        ThreadPoolExecutor exec = new ThreadPoolExecutor(max, max, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>(), r -> newThread(r, name, true));
        exec.allowCoreThreadTimeOut(true); // Unbounded queues don't grow past corePoolSize, so we increase that to the maxPoolSize and allow the core threads to expire
        return exec;
    }

    /** @see #cachedExecutor(String, int) */
    public static ExecutorService cachedExecutor(@Nullable String name){
        return cachedExecutor(name, OS.cores);
    }

    /** @see #cachedExecutor(String, int) */
    public static ExecutorService cachedExecutor(){
        return cachedExecutor(null);
    }

    /** @return an executor with no max thread count. threads expire after 1 minute of inactivity
     *  @param min the number of threads to keep alive at all times after they are first started */
    public static ExecutorService unboundedExecutor(@Nullable String name, int min){
        return new ThreadPoolExecutor(min, Integer.MAX_VALUE, 1, TimeUnit.MINUTES, new SynchronousQueue<>(), r -> newThread(r, name, true));
    }

    /** @see #unboundedExecutor(String, int) */
    public static ExecutorService unboundedExecutor(@Nullable String name){
        return unboundedExecutor(name, 0);
    }

    /** @see #unboundedExecutor(String, int) */
    public static ExecutorService unboundedExecutor(){
        return unboundedExecutor(null);
    }

    /** Shuts down the executor and waits for its termination indefinitely. */
    public static void await(ExecutorService exec){
        try{
            exec.shutdown();
            exec.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }

    public static void sleep(long ms){
        try{
            Thread.sleep(ms);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }

    public static void sleep(long ms, int ns){
        try{
            Thread.sleep(ms, ns);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }
    }

    /** Throws an exception in the main game thread.*/
    public static void throwAppException(Throwable t){
        Core.app.post(() -> {
            if(t instanceof RuntimeException){
                throw ((RuntimeException)t);
            }else{
                throw new RuntimeException(t);
            }
        });
    }

    /** Starts a new non-daemon thread.*/
    public static Thread thread(Runnable runnable){
        return thread(null, runnable);
    }

    /** Starts a new non-daemon thread.*/
    public static Thread thread(@Nullable String name, Runnable runnable){
        Thread thread = newThread(runnable, name, false);
        thread.start();
        return thread;
    }

    /** Starts a new daemon thread.*/
    public static Thread daemon(Runnable runnable){
        return daemon(null, runnable);
    }

    /** Starts a new daemon thread.*/
    public static Thread daemon(@Nullable String name, Runnable runnable){
        Thread thread = newThread(runnable, name, true);
        thread.start();
        return thread;
    }

    private static Thread newThread(Runnable r, @Nullable String name, boolean daemon){
        Thread thread = name == null ? new Thread(r) : new Thread(r, name);
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((t, e) -> throwAppException(e));
        return thread;
    }

    public static class DisposableRef<T> extends PhantomReference<T>{
        DisposableRef<?> prev = this, next = this;

        private final DisposableRef<?> list;
        private final ReferenceQueue<T> q;

        public DisposableRef(ReferenceQueue<T> q){
            super(null, null);
            list = this;
            this.q = q;
        }

        public DisposableRef(T referent, DisposableRef<? super T> list){
            super(referent, list.q);
            this.list = list;
            this.q = null;
            insert();
        }

        private void insert(){
            synchronized(list){
                prev = list;
                next = list.next;
                next.prev = this;
                list.next = this;
            }
        }

        public boolean remove(){
            synchronized(list){
                if(next != this){
                    next.prev = prev;
                    prev.next = next;
                    prev = this;
                    next = this;
                    return true;
                }
                return false;
            }
        }

        public boolean isListEmpty(){
            synchronized(list){
                return list == list.next;
            }
        }
    }
}
