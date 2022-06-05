package arc.graphics.g2d;

import arc.graphics.*;
import arc.graphics.gl.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;

import java.util.*;
import java.util.concurrent.*;

public class SortedSpriteBatch extends SpriteBatch{

    protected Seq<DrawRequest> requestPool = new Seq<>(false, 10_000);
    protected Seq<DrawRequest> requests = new Seq<>(true, 10_000, DrawRequest.class);
    protected boolean sort;
    protected boolean flushing;
    protected FloatSeq requestZ = new FloatSeq();

    @Override
    protected void setSort(boolean sort){
        if(this.sort != sort){
            flush();
        }
        this.sort = sort;
    }

    @Override
    protected void setShader(Shader shader, boolean apply){
        if(!flushing && sort){
            throw new IllegalArgumentException("Shaders cannot be set while sorting is enabled. Set shaders inside Draw.draw(z, ...).");
        }
        super.setShader(shader, apply);
    }

    @Override
    protected void setBlending(Blending blending){
        if(flushing){
            super.setBlending(blending);
        }else{
            this.blending = blending;
        }
    }

    @Override
    protected void draw(Texture texture, float[] spriteVertices, int offset, int count){
        if(sort && !flushing){
            for(int i = offset; i < count; i += SPRITE_SIZE){
                DrawRequest req = obtain();
                req.z = z;
                System.arraycopy(spriteVertices, i, req.vertices, 0, req.vertices.length);
                req.texture = texture;
                req.blending = blending;
                req.run = null;
                if(fs) {
                    // java 9+ has a faster StackWalker class.
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    StackTraceElement curr = null;
                    for (int j = 0; j < st.length; j++) {
                        String p = st[j].getClassName();
                        if (p.startsWith("arc") || p.startsWith("java") || p.contains(".graphics.")) {
                            continue;
                        }
                        curr = st[j];
                        break;
                    }
                    if (curr != null) {
                        req.from = Strings.format("@::@ (@:@)", curr.getClassName(), curr.getMethodName(), curr.getFileName(), curr.getLineNumber());
                    } else req.from = "";
                } else req.from = "";
                requests.add(req);
                requestZ.add(z);
            }
        }else{
            super.draw(texture, spriteVertices, offset, count);
        }
    }

    @Override
    protected void draw(TextureRegion region, float x, float y, float originX, float originY, float width, float height, float rotation){
        //0 alpha sprites are skipped
        //this *might* interfere with weird custom blending...
        if((Float.floatToRawIntBits(colorPacked) & 0xFF000000) == 0 && customShader == null){
            return;
        }

        if(sort && !flushing){
            DrawRequest req = obtain();
            req.x = x;
            req.y = y;
            req.z = z;
            req.originX = originX;
            req.originY = originY;
            req.width = width;
            req.height = height;
            req.color = colorPacked;
            req.rotation = rotation;
            req.region = region;
            req.mixColor = mixColorPacked;
            req.blending = blending;
            req.texture = null;
            req.run = null;
            requests.add(req);
            requestZ.add(z);
        }else{
            super.draw(region, x, y, originX, originY, width, height, rotation);
        }
    }

    @Override
    protected void draw(Runnable request){
        if(sort && !flushing){
            DrawRequest req = obtain();
            req.run = request;
            req.blending = blending;
            req.mixColor = mixColorPacked;
            req.color = colorPacked;
            req.z = z;
            req.texture = null;
            requests.add(req);
            requestZ.add(z);
        }else{
            super.draw(request);
        }
    }

    protected DrawRequest obtain(){
        return requestPool.size > 0 ? requestPool.pop() : new DrawRequest();
    }

    @Override
    protected void flush(){
        flushRequests();
        super.flush();
    }

    protected void flushRequests(){
        if(!flushing && !requests.isEmpty()){
            flushing = true;
            sortRequests();
            float preColor = colorPacked, preMixColor = mixColorPacked;
            Blending preBlending = blending;

            for(int j = 0; j < requests.size; j++){
                DrawRequest req = requests.items[j];

                colorPacked = req.color;
                mixColorPacked = req.mixColor;

                super.setBlending(req.blending);

                if(req.run != null){
                    req.run.run();
                }else if(req.texture != null){
                    super.draw(req.texture, req.vertices, 0, req.vertices.length);
                }else{
                    super.draw(req.region, req.x, req.y, req.originX, req.originY, req.width, req.height, req.rotation);
                }
            }

            colorPacked = preColor;
            mixColorPacked = preMixColor;
            color.abgr8888(colorPacked);
            mixColor.abgr8888(mixColorPacked);
            blending = preBlending;

            requestPool.addAll(requests);
            requests.size = 0;
            requestZ.size = 0;

            flushing = false;
        }
    }

    public static boolean debug = false, dump = false, fs = false, zs = false, mt = true, radix = false;
    Point3[] contiguous = new Point3[2048], contiguousCopy = new Point3[2048];
    { for(int i = 0; i < contiguous.length; i++) contiguous[i] = new Point3(); }
    ObjectIntMap<String> map = new ObjectIntMap<>();
    ForkJoinPool commonPool = ForkJoinPool.commonPool();
    DrawRequest[] copy = new DrawRequest[0];
    int[] locs = new int[contiguous.length];

    protected void sortRequests(){
        if (mt) {
            sortRequestsMT();
        } else {
            sortRequestsST();
        }
    }

    protected void sortRequestsMT(){
        Time.mark(); Time.mark();
        final int numRequests = requests.size;
        if(copy.length < numRequests) copy = new DrawRequest[numRequests + (numRequests >> 3)];
        final DrawRequest[] items = requests.items, itemCopy = copy;
        final float[] itemZ = requestZ.items;
        Future<?> initTask = commonPool.submit(() -> System.arraycopy(items, 0, itemCopy, 0, numRequests));

        float t_init = Time.elapsed(); Time.mark();

        Point3[] contiguous = this.contiguous;
        int ci = 0, cl = contiguous.length;
        float z = itemZ[0];
        int startI = 0;
        // Point3: <z, index, length>
        for(int i = 1; i < numRequests; i++){
            if(itemZ[i] != z){ // if contiguous section should end
                contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, i - startI);
                if(ci >= cl){
                    cl <<= 1;
                    Point3[] contiguous2 = new Point3[cl];
                    System.arraycopy(contiguous, 0, contiguous2, 0, ci);
                    for(int j = ci; j < cl; j++) contiguous2[j] = new Point3();
                    contiguous = contiguous2;
                }
                z = itemZ[i];
                startI = i;
            }
        }
        contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, numRequests - startI);
        float t_cont = Time.elapsed(); Time.mark();

        final int L = ci;

        this.contiguous = contiguous;
        if(contiguousCopy.length < contiguous.length){
            this.contiguousCopy = new Point3[contiguous.length];
        }

        //Arrays.parallelSort(contiguous, 0, L, Structs.comparingInt(p -> p.x));
        contiguous = radix ? RadixSort.radixSortST(contiguous, contiguousCopy, L) : CountingSort.countingSortST(contiguous, contiguousCopy, L);

        float t_sort = Time.elapsed(); Time.mark();

        if(locs.length < L + 1) locs = new int[L + L / 10];
        int[] locs = this.locs;
        for(int i = 0; i < L; i++){
            locs[i + 1] = locs[i] + contiguous[i].z;
        }
        try {
            initTask.get();
        } catch (Exception ignored){
            System.arraycopy(items, 0, itemCopy, 0, numRequests);
        }
        PopulateTask.tasks = contiguous;
        PopulateTask.src = itemCopy;
        PopulateTask.dest = items;
        PopulateTask.locs = locs;
        commonPool.invoke(new PopulateTask(0, L));
        float t_cpy = Time.elapsed();
        float elapsed = Time.elapsed();
        if(debug) {
            Log.debug("total: @ | size: @ -> @ | init: @ | contiguous: @ | sort: @ | populate: @",
                elapsed, numRequests, L, t_init, t_cont, t_sort, t_cpy);
            debugInfo();
        }
    }
    protected void sortRequestsST(){ // Non-threaded implementation for weak devices
        Time.mark(); Time.mark();
        final int numRequests = requests.size;
        if(copy.length < numRequests) copy = new DrawRequest[numRequests + (numRequests >> 3)];
        final DrawRequest[] items = copy;
        final float[] itemZ = this.requestZ.items;
        System.arraycopy(requests.items, 0, items, 0, numRequests);
        float t_init = Time.elapsed(); Time.mark();
        Point3[] contiguous = this.contiguous;
        int ci = 0, cl = contiguous.length;
        float z = itemZ[0];
        int startI = 0;
        // Point3: <z, index, length>
        for(int i = 1; i < numRequests; i++){
            if(itemZ[i] != z){ // if contiguous section should end
                contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, i - startI);
                if(ci >= cl){
                    cl <<= 1;
                    Point3[] contiguous2 = new Point3[cl];
                    System.arraycopy(contiguous, 0, contiguous2, 0, ci);
                    for(int j = ci; j < cl; j++) contiguous2[j] = new Point3();
                    contiguous = contiguous2;
                }
                z = itemZ[i];
                startI = i;
            }
        }
        contiguous[ci++].set(Float.floatToRawIntBits(z + 16f), startI, numRequests - startI);
        float t_cont = Time.elapsed(); Time.mark();

        final int L = ci;

        this.contiguous = contiguous;
        if(contiguousCopy.length < contiguous.length){
            contiguousCopy = new Point3[contiguous.length];
        }
        contiguous = radix ? RadixSort.radixSortST(contiguous, contiguousCopy, L) : CountingSort.countingSortST(contiguous, contiguousCopy, L);
        //Arrays.sort(contiguous, 0, L, Structs.comparingInt(p -> p.x));
        float t_sort = Time.elapsed(); Time.mark();

        int ptr = 0;
        DrawRequest[] dest = requests.items;
        for(int i = 0; i < L; i++){
            Point3 point = contiguous[i];
            final int length = point.z;
            if(length < 10){
                final int end = point.y + length;
                for(int sj = point.y, dj = ptr; sj < end ; sj++, dj++){
                    requests.items[dj] = items[sj];
                }
            } else System.arraycopy(items, point.y, dest, ptr, length);
            ptr += point.z;
        }
        float t_cpy = Time.elapsed();
        float elapsed = Time.elapsed();
        if(debug) {
            Log.debug("total: @ | size: @ -> @ | init: @ | contiguous: @ | sort: @ | populate: @ | reset: -",
                elapsed, numRequests, L, t_init, t_cont, t_sort, t_cpy);
            debugInfo();
        }
    }
    public void debugInfo() {
        if (debug) {
            StringBuilder out = new StringBuilder();
            if (zs) {
                float prevZ = Float.MAX_VALUE;
                int length = 0;
                for (int i = 0; i < requests.size; i++) {
                    float currZ = requests.items[i].z;
                    if (currZ != prevZ) {
                        if (prevZ != Float.MAX_VALUE) {
                            out.append(prevZ);
                            out.append(": ");
                            out.append(length);
                            out.append(", ");
                        }
                        length = 0;
                        prevZ = currZ;
                    }
                    ++length;
                }
                out.delete(out.length() - 2, out.length());
            }
            if (fs) {
                if (zs) out.append("\n");
                for (DrawRequest dr : requests) {
                    map.put(dr.from, map.get(dr.from, 0) + 1);
                }
                map.entries().forEach(e -> {
                    out.append(e.key);
                    out.append(": ");
                    out.append(e.value);
                    out.append(", ");
                });
                out.delete(out.length() - 2, out.length());
                map.clear();
            }
            zs = fs = false;
            if (out.length() > 0) Log.debug(out.toString());
        }
        //if(debug) Log.debug("elapsed: @ | size: @ | buckets: @", elapsed, requests.size, i);
        if (debug && dump) {
            StringBuilder out = new StringBuilder("Result dump:\n");
            for (DrawRequest dr : requests) {
                out.append(dr.z);
                out.append(" ");
            }
            Log.debug(out);
            debug = dump = false;
        }
    }
    static class CountingSort{
        private static int keyLength = 100; // The maximum number of unique values to sort
        private static int[] keys = new int[keyLength], locs = new int[keyLength], sortedToInsertion = new int[keyLength];
        public static Point3[] countingSortST(Point3[] arr, Point3[] swap, final int end){
            int unique = 0, keyLength = CountingSort.keyLength;
            int[] locs = CountingSort.locs, keys = CountingSort.keys, sortedToInsertion = CountingSort.sortedToInsertion;
            for(int i = 0; i < end; i++){
                int num = arr[i].x;
                int loc = Arrays.binarySearch(keys, 0, unique, num);
                if(loc < 0){
                    loc = -loc - 1;
                    if(unique >= keyLength){
                        CountingSort.keyLength = keyLength = keyLength * 3 / 2;
                        CountingSort.keys = keys = Arrays.copyOf(keys, keyLength);
                        CountingSort.locs = locs = Arrays.copyOf(locs, keyLength);
                        CountingSort.sortedToInsertion = sortedToInsertion = Arrays.copyOf(sortedToInsertion, keyLength);
                    }
                    System.arraycopy(keys, loc, keys, loc + 1, unique - loc);
                    System.arraycopy(sortedToInsertion, loc, sortedToInsertion, loc + 1, unique - loc);
                    arr[i].x = sortedToInsertion[loc] = unique;
                    keys[loc] = num;
                    locs[unique++] = 1;
                } else {
                    locs[arr[i].x = sortedToInsertion[loc]]++;
                }
            }
            for(int i = 1; i < unique; i++){
                locs[sortedToInsertion[i]] += locs[sortedToInsertion[i - 1]];
            }
            for(int i = end - 1; i >= 0; i--){
                Point3 curr = arr[i];
                swap[--locs[curr.x]] = curr;
            }
            return swap;
        }
    }
    static class RadixSort{
        private final static int bits = 13, radix_length = 1 << bits, mask = radix_length - 1, runs = (26 + bits - 1) / bits;
        private final static int[] radixBuckets = new int[radix_length], bucketsBlank = new int[radix_length];
        // For up to z = 256, we only need to radix the last 26 bits.
        public static Point3[] radixSortST(Point3[] arr, Point3[] swap, final int end){
            int[] keys = RadixSort.radixBuckets;
            for(int d = 0; d < runs; d++){
                System.arraycopy(bucketsBlank, 0, keys, 0, radix_length);
                for(int i = 0; i < end; i++){
                    keys[arr[i].x & mask]++;
                }
                for(int i = 1; i < radix_length; i++){
                    keys[i] += keys[i - 1];
                }
                for(int i = end - 1; i >= 0; i--){
                    Point3 curr = arr[i];
                    swap[--keys[curr.x & mask]] = curr;
                    curr.x >>= bits;
                }
                Point3[] temp = arr;
                arr = swap;
                swap = temp;
            }
            return arr;
        }
    }
    static class PopulateTask extends RecursiveAction{
        int from, to;
        static Point3[] tasks;
        static DrawRequest[] src;
        static DrawRequest[] dest;
        static int[] locs;
        //private static final int threshold = 256;
        PopulateTask(int from, int to){
            this.from = from;
            this.to = to;
        }
        @Override
        /*
        protected void compute(){
            if(to - from > threshold){
                int mid = (from + to) >> 1;
                PopulateTask t1 = new PopulateTask(from, mid), t2 = new PopulateTask(mid, to);
                invokeAll(t1, t2);
            } else {
                Point3[] tasks = PopulateTask.tasks;
                int[] locs = PopulateTask.locs;
                for(int i = from; i < to; i++){
                    Point3 point = tasks[i];
                    System.arraycopy(src, point.y, dest, locs[i], point.z);
                }
            }
        }
         */
        protected void compute(){
            int[] locs = PopulateTask.locs;
            if(to - from > 1 && locs[to] - locs[from] > 2048){
                int half = (locs[to] + locs[from]) >> 1;
                int mid = Arrays.binarySearch(locs, from, to, half);
                if(mid < 0) mid = -mid - 1;
                if(mid != from && mid != to) {
                    invokeAll(new PopulateTask(from, mid), new PopulateTask(mid, to));
                    return;
                }
            }
            DrawRequest[] src = PopulateTask.src, dest = PopulateTask.dest;
            for(int i = from; i < to; i++){
                Point3 point = tasks[i];
                final int length = point.z;
                if(length < 10){
                    final int end = point.y + length;
                    for(int sj = point.y, dj = locs[i]; sj < end ; sj++, dj++){
                        dest[dj] = src[sj];
                    }
                } else System.arraycopy(src, point.y, dest, locs[i], Math.min(length, dest.length - locs[i]));
            }
        }
    }
}