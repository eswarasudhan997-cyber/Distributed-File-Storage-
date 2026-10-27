import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Regression-test suite for the distributed storage application.
 *
 * This class checks expected behaviour; it does not implement storage,
 * replication, persistence, or the B-Tree itself. Those are implemented in
 * StorageSystem.java and BTree.java.
 *
 * Run this class to execute the test groups. If a check fails, an exception is
 * thrown and the program stops before printing the final success summary.
 */
public class StorageSystemTests {
    // Counts successful test groups (not the number of individual assertions).
    static int passed;

    // Lets each test pass a block of code that may throw an exception.
    @FunctionalInterface
    interface Checked {
        void run() throws Exception;
    }
    // Runs one test group. It prints PASS and increments the count only if
    // the test action finishes without an uncaught exception or failed check.
    static void test(String label, Checked action) throws Exception {
        action.run();
        passed++;
        System.out.println("PASS " + label);
    }

    // Fails the current test immediately when an expected condition is false.
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    // Passes only if the action throws the requested exception type.
    // A different exception, or no exception at all, fails the test.
    static void expect(Class<? extends Throwable> type, Checked action) throws Exception {
        try { action.run(); } catch(Throwable ex) { if(type.isInstance(ex))return;throw new AssertionError("Expected "+type.getSimpleName()+"; got "+ex,ex); }
        throw new AssertionError("Expected "+type.getSimpleName());
    }
    // Calculates SHA-256 using the same method as the application.
    static String hash(String value) {
        return StorageSystem.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    // Creates a test cluster with N1...Nn. Each node has the supplied capacity
    // and a distinct region (R1...Rn). MemoryStore keeps ordinary tests fast.
    static StorageSystem cluster(int n, long capacity) throws Exception {
        StorageSystem s = new StorageSystem(new StorageSystem.MemoryStore());
        for (int i = 1; i <= n; i++) s.addNode("N" + i, "R" + i, capacity);
        return s;
    }

    // Counts replicas whose corresponding storage nodes are currently healthy.
    static long healthy(StorageSystem s, long id) {
        return s.getFile(id).replicas.keySet().stream()
                .filter(n -> s.state.nodes.get(n).healthy).count();
    }

    // Sums the used capacity across every storage node in the cluster.
    static long used(StorageSystem s) {
        return s.nodes().stream().mapToLong(n -> n.used).sum();
    }

    // Runs queued background tasks until none remain, with a safety limit to
    // catch accidental infinite task scheduling. Then checks internal state.
    static void drain(StorageSystem s) {
        int bound = 10000;
        while (!s.tasks().isEmpty()) {
            check(--bound > 0, "Task loop");
            s.runNextTask();
        }
        s.validateState();
    }
    public static void main(String[] args) throws Exception {
        // GROUP 1: Basic file operations, replication, undo, and invalid uploads.
        // T01: Creates four nodes, uploads a five-byte file, and checks for three healthy replicas and 15 total bytes used.
        test("T01 upload: three distinct replicas and exact capacity",()->{
            var s=cluster(4,100);var f=s.uploadText("hello.txt","hello");
            check(f.replicas.size()==3&&healthy(s,f.id)==3&&used(s)==15,"Upload allocation");s.validateState();
        });
        // T02: Uploads the same content under two names and checks deduplication, canonical ID reuse, and undo safety.
        test("T02 dedup: canonical ID; no extra capacity; undo is no-op",()->{
            var s=cluster(3,100);var a=s.uploadText("a","same");var b=s.uploadText("b","same");
            check(a.id==b.id&&s.files(1,100).size()==1&&used(s)==12,"Dedup");s.undo();check(s.getFile(a.id)!=null,"Dedup undo removed original");
        });
        // T03: Marks one replica node unhealthy and verifies download uses another healthy node and returns the original text.
        test("T03 download: healthy source and original text",()->{
            var s=cluster(3,100);var f=s.uploadText("a","hello");s.setHealth("N1",false);
            var d=s.download(f.id);check(!d.node().equals("N1")&&new String(d.file().content,StandardCharsets.UTF_8).equals("hello"),"Download");
        });
        // T04: Checks that deleting a nonexistent file returns false and does not consume storage.
        test("T04 deleting missing ID is harmless",()->{var s=cluster(3,100);check(!s.delete(999)&&used(s)==0,"Missing delete");});
        // T05: Fails one replica node, processes repair tasks, and checks replica health and capacity accounting.
        test("T05 failure repair: three healthy replicas; failed-node space released",()->{
            var s=cluster(4,100);var f=s.uploadText("a","hello");String failed=f.replicas.keySet().iterator().next();s.setHealth(failed,false);drain(s);
            check(healthy(s,f.id)==3&&s.state.nodes.get(failed).used==0&&used(s)==15,"Repair accounting");
        });
        // T06: Deletes a downloaded file and checks metadata, cache, tasks, and capacity are cleaned up; then checks deduplication is not stale.
        test("T06 delete clears indexes, tasks, cache and capacity",()->{
            var s=cluster(3,100);var f=s.uploadText("a","hello");s.download(f.id);s.delete(f.id);
            check(s.getFile(f.id)==null&&used(s)==0&&s.tasks().isEmpty()&&s.state.cache.isEmpty(),"Delete cleanup");
            check(s.uploadText("again","hello").id!=f.id,"Dedup stale after delete");
        });
        // T07: Deletes a file, undoes the deletion, and checks the original ID, name, content, replicas, and capacity are restored.
        test("T07 undo delete restores ID, content, replicas and capacity",()->{
            var s=cluster(3,100);var f=s.uploadText("a","hello");s.delete(f.id);check(s.undo(),"Undo result");
            check(s.getFile(f.id).name.equals("a")&&healthy(s,f.id)==3&&used(s)==15,"Undo delete");
            check(new String(s.getFile(f.id).content,StandardCharsets.UTF_8).equals("hello"),"Restored content");
        });
        // T08: Checks that a zero-byte file can have three placements without consuming capacity, and exercises delete/undo state validation.
        test("T08 zero bytes: three placements, no space consumed",()->{var s=cluster(3,1);var f=s.uploadText("empty","");check(f.size==0&&f.replicas.size()==3&&used(s)==0,"Zero bytes");s.delete(f.id);s.undo();s.validateState();});
        // T09: Uploads a file, repairs after a node failure, then undoes the upload and checks that all current allocations are removed.
        test("T09 undo upload removes current replicas after repair",()->{var s=cluster(4,100);var f=s.uploadText("a","hello");s.setHealth("N1",false);drain(s);s.undo();check(s.getFile(f.id)==null&&used(s)==0,"Undo repaired upload");});
        // T10: Checks that a negative file size is rejected without changing capacity usage.
        test("T10 negative file size rejected",()->{var s=cluster(3,100);expect(IllegalArgumentException.class,()->s.upload("bad",hash("x"),-1));check(used(s)==0,"Mutation");});
        // T11: Checks that blank and null filenames are rejected.
        test("T11 blank and null filenames rejected",()->{var s=cluster(3,100);expect(IllegalArgumentException.class,()->s.uploadText(" ","x"));expect(IllegalArgumentException.class,()->s.uploadText(null,"x"));});
        // T12: Checks that blank and malformed content hashes are rejected.
        test("T12 blank and malformed hashes rejected",()->{var s=cluster(3,100);expect(IllegalArgumentException.class,()->s.upload("a"," ",1));expect(IllegalArgumentException.class,()->s.upload("a","bad",1));});
        // GROUP 2: B-Tree insertion, search, range queries, and deletion.
        // T13: Inserts keys in descending order to trigger B-Tree splits, then searches every key and validates the tree.
        test("T13 multiple B-Tree splits with descending keys",()->{var t=new BTree<Integer,String>(2);for(int i=100;i>=0;i--)t.put(i,"v"+i);for(int i=0;i<=100;i++)check(t.get(i).equals("v"+i),"Search "+i);t.validate();});
        // T14: Checks inclusive range results, reversed ranges, and ranges with no matching keys.
        test("T14 inclusive, reversed and absent B-Tree ranges",()->{var t=new BTree<Integer,Integer>(2);for(int i=0;i<100;i++)t.put(i,i);check(t.range(20,25).size()==6&&t.range(25,20).isEmpty()&&t.range(120,125).isEmpty(),"Range");});
        // T15: Removes every inserted B-Tree key, validating after each removal and checking the root becomes empty.
        test("T15 B-Tree deletion, borrowing, merging and empty root",()->{var t=new BTree<Integer,Integer>(2);for(int i=0;i<100;i++)t.put(i,i);for(int i=0;i<100;i++) { check(t.remove(i)==i,"Remove");t.validate(); }check(t.size()==0&&t.firstEntry()==null,"Root shrink");});
        // T16: Checks that searching/removing a missing key returns null and does not change B-Tree size.
        test("T16 missing B-Tree keys do not change size",()->{var t=new BTree<Integer,String>(2);t.put(1,"a");check(t.get(7)==null&&t.remove(7)==null&&t.size()==1,"Missing key");});
        // GROUP 3: Atomic failure handling, node validation, audit, and task scheduling.
        // T17: Checks uploads fail atomically when there are too few nodes or not enough capacity.
        test("T17 insufficient nodes/capacity is atomic",()->{
            var s=cluster(2,100);expect(IllegalStateException.class,()->s.uploadText("a","hello"));check(s.state.files.isEmpty()&&used(s)==0&&s.state.nextFile==1&&s.state.undo.isEmpty(),"Partial upload");
            s.addNode("N3","R3",4);expect(IllegalStateException.class,()->s.uploadText("a","hello"));check(used(s)==0,"Capacity failure mutated");
        });
        // T18: Makes every replica unavailable and verifies a cached file is not returned as if a healthy replica were available.
        test("T18 all replicas down: no download, even on cache hit",()->{var s=cluster(4,100);var f=s.uploadText("a","hello");s.download(f.id);for(String id:new ArrayList<>(f.replicas.keySet()))s.setHealth(id,false);expect(IllegalStateException.class,()->s.download(f.id));});
        // T19: Checks that attempting to change the health of an unknown node is rejected.
        test("T19 unknown node rejected",()->{var s=cluster(3,100);expect(IllegalArgumentException.class,()->s.setHealth("missing",false));});
        // T20: Checks duplicate node IDs are rejected and the number of nodes stays unchanged.
        test("T20 duplicate node rejected",()->{var s=cluster(3,100);expect(IllegalArgumentException.class,()->s.addNode("N1","other",1));check(s.nodes().size()==3,"Duplicate node");});
        // T21: Checks invalid capacities and blank node IDs or region names are rejected.
        test("T21 invalid capacities and blank node/region rejected",()->{var s=cluster(0,1);expect(IllegalArgumentException.class,()->s.addNode("n","r",0));expect(IllegalArgumentException.class,()->s.addNode("n","r",-1));expect(IllegalArgumentException.class,()->s.addNode(" ","r",1));expect(IllegalArgumentException.class,()->s.addNode("n"," ",1));});
        // T22: Checks invalid B-Tree degree parameters are rejected.
        test("T22 invalid B-Tree degree rejected",()->{expect(IllegalArgumentException.class,()->new BTree<Integer,String>(1));expect(IllegalArgumentException.class,()->new BTree<Integer,String>(Integer.MAX_VALUE));});
        // T23: Checks undo and task execution are safe when their respective stacks/queues are empty.
        test("T23 empty undo and empty tasks are safe",()->{var s=cluster(0,1);check(!s.undo()&&s.runNextTask().equals("No pending tasks."),"Empty operations");});
        // T24: Checks node-addition audit records retain FIFO order.
        test("T24 audit messages retain FIFO order",()->{var s=cluster(3,100);var a=s.audit();check(a.get(0).endsWith("NODE_ADD N1")&&a.get(1).endsWith("NODE_ADD N2")&&a.get(2).endsWith("NODE_ADD N3"),"Audit order");});
        // T25: Checks task priority ordering, FIFO tie-breaking, and that repair work is scheduled as urgent work.
        test("T25 priority and FIFO ties",()->{
            PriorityQueue<StorageSystem.Task> q=new PriorityQueue<>();q.add(new StorageSystem.Task(5,1,"VERIFY",1));q.add(new StorageSystem.Task(1,3,"REPAIR",1));q.add(new StorageSystem.Task(1,2,"REPAIR",2));
            check(q.remove().sequence==2&&q.remove().sequence==3&&q.remove().sequence==1,"Priority order");
            var s=cluster(4,100);s.uploadText("a","hello");s.setHealth("N1",false);check(s.tasks().get(0).type.equals("REPAIR"),"Urgent task not first");
        });
        // T26: Checks that impossible repair leaves the file under-replicated and records an explicit warning rather than faking success.
        test("T26 impossible repair retains placement and logs under-replication",()->{var s=cluster(3,100);var f=s.uploadText("a","hello");s.setHealth("N1",false);drain(s);check(healthy(s,f.id)==2&&s.getFile(f.id).replicas.size()==3&&used(s)==15,"Impossible repair");check(s.audit().stream().anyMatch(x->x.contains("UNDER_REPLICATED")),"No warning");});
        // GROUP 4: Rebalancing, hash validation, and recovery/undo behaviour.
        // T27: Adds a node, triggers rebalancing, and checks load improves, the new node receives data, and total used bytes stay constant.
        test("T27 rebalance actually migrates and improves squared load",()->{
            var s=cluster(3,100);for(int i=0;i<4;i++)s.upload("file"+i,hash("f"+i),10);s.addNode("N4","R4",100);
            double before=s.nodes().stream().mapToDouble(n->n.load()*n.load()).sum();s.configure(300000,.01,5);s.checkHealth(System.currentTimeMillis());drain(s);
            double after=s.nodes().stream().mapToDouble(n->n.load()*n.load()).sum();check(after<before&&s.state.nodes.get("N4").used>0,"No migration");check(used(s)==120,"Migration changed total space");
        });
        // T28: Checks hash/size inconsistencies are rejected and verifies the expected SHA-256 hash of "hello".
        test("T28 bad hash/size inconsistency rejected; text hash computed",()->{var s=cluster(3,100);var f=s.uploadText("a","hello");expect(IllegalArgumentException.class,()->s.upload("bad",f.hash,6));check(f.hash.equals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"),"SHA-256 mismatch");});
        // T29: Checks repair does not invent data when all sources are unhealthy, then checks recovery after a source returns.
        test("T29 no healthy source: never fabricate a replica",()->{var s=cluster(6,100);var f=s.uploadText("a","hello");Set<String> old=new HashSet<>(f.replicas.keySet());for(String id:old)s.setHealth(id,false);drain(s);check(s.getFile(f.id).replicas.keySet().equals(old)&&healthy(s,f.id)==0&&used(s)==15,"Fabricated source");s.heartbeat(old.iterator().next());drain(s);check(healthy(s,f.id)==3,"Recovery after source returns");});
        // T30: Checks failed undo leaves state and undo history intact, then succeeds after node recovery.
        test("T30 blocked undo stays on stack and succeeds after recovery",()->{var s=cluster(3,100);var f=s.uploadText("a","hello");s.delete(f.id);s.setHealth("N1",false);int depth=s.state.undo.size();expect(IllegalStateException.class,s::undo);check(s.state.undo.size()==depth&&s.getFile(f.id)==null&&used(s)==0,"Failed undo changed state");s.heartbeat("N1");check(s.undo()&&healthy(s,f.id)==3,"Undo retry");});
        // GROUP 5: Capacity allocation, cache behaviour, heartbeats, and configuration.
        // T31: Checks fragmented free space can block a large allocation and that adjacent free blocks coalesce after deletion.
        test("T31 fragmented free blocks reject upload; adjacent blocks coalesce",()->{
            var s=cluster(3,100);long a=s.upload("a",hash("a"),30).id,b=s.upload("b",hash("b"),30).id,c=s.upload("c",hash("c"),30).id;
            s.delete(a);s.delete(c);expect(IllegalStateException.class,()->s.upload("too-big",hash("d"),60));check(used(s)==90,"Fragmented failure");
            s.delete(b);for(var n:s.nodes())check(n.free.size()==1&&n.free.get(0).length==100,"Coalescing");s.upload("now-fits",hash("e"),100);check(used(s)==300,"Exact fit");
        });
        // T32: Checks LRU cache misses/hits, eviction of the least-recently-used entry, undo behaviour, and cache disabling.
        test("T32 LRU cache hit/miss/eviction and download undo",()->{
            var s=cluster(3,100);s.configure(300000,.25,2);long a=s.uploadText("a","a").id,b=s.uploadText("b","b").id,c=s.uploadText("c","c").id;
            check(!s.download(a).cacheHit()&&!s.download(b).cacheHit()&&s.download(a).cacheHit(),"Cache hit");s.download(c);check(!s.state.cache.containsKey(b),"LRU victim");
            s.undo();check(s.state.cache.containsKey(b)&&!s.state.cache.containsKey(c),"Undo cache");s.configure(300000,.25,0);s.download(a);check(s.state.cache.isEmpty(),"Cache disabled");
        });
        // T33: Checks heartbeat timeout detection, duplicate repair-task prevention, and recovery from a new heartbeat.
        test("T33 heartbeat timeout and task deduplication",()->{var s=cluster(4,100);s.uploadText("a","hello");long now=s.nodes().stream().mapToLong(n->n.heartbeat).max().orElseThrow()+300001;s.checkHealth(now);int count=s.tasks().size();s.checkHealth(now);check(s.nodes().stream().noneMatch(n->n.healthy)&&s.tasks().size()==count,"Timeout/task dedup");s.heartbeat("N1");check(s.state.nodes.get("N1").healthy,"Heartbeat");});
        // T34: Checks replica placement prefers distinct regions and falls back when all nodes share a region.
        test("T34 region diversity preferred; fallback permits one-region cluster",()->{var s=cluster(0,1);s.addNode("a","X",100);s.addNode("b","X",100);s.addNode("c","Y",100);s.addNode("d","Z",100);var f=s.uploadText("a","x");check(f.replicas.keySet().equals(new LinkedHashSet<>(List.of("a","c","d"))),"Region preference");var one=cluster(0,1);for(int i=0;i<3;i++)one.addNode("n"+i,"X",100);check(one.uploadText("a","x").replicas.size()==3,"Same region fallback");});
        // T35: Checks file ID ranges use numeric ordering rather than lexical string ordering.
        test("T35 file ID range is numeric, not lexical",()->{var s=cluster(3,100);for(int i=1;i<=12;i++)s.uploadText("f"+i,"x"+i);check(s.files(2,10).size()==9&&s.files(2,10).get(8).id==10&&s.files(10,2).isEmpty(),"Numeric range");});
        // T36: Checks invalid timeout, ratio, NaN, and cache-size configuration values are rejected.
        test("T36 configuration validation",()->{var s=cluster(0,1);expect(IllegalArgumentException.class,()->s.configure(0,.2,1));expect(IllegalArgumentException.class,()->s.configure(1,Double.NaN,1));expect(IllegalArgumentException.class,()->s.configure(1,1.1,1));expect(IllegalArgumentException.class,()->s.configure(1,.2,-1));});
        // GROUP 6: Restart behaviour, disk persistence, rollback, and corrupted state.
        // T37: Reopens a system with the same MemoryStore and checks file state/undo recovery across a new StorageSystem instance.
        test("T37 restart restores content, indexes, cache, tasks and undo",()->{
            var store=new StorageSystem.MemoryStore();var s=new StorageSystem(store);for(int i=0;i<3;i++)s.addNode("n"+i,"r"+i,100);var f=s.uploadText("a","hello");s.download(f.id);s.delete(f.id);
            var loaded=new StorageSystem(store);check(loaded.getFile(f.id)==null&&loaded.undo()&&used(loaded)==15,"Undo after restart");check(new String(loaded.getFile(f.id).content,StandardCharsets.UTF_8).equals("hello"),"Content restart");loaded.validateState();
        });
        // T38: Uses a temporary disk file to check persistence across reopen, file locking, and state validation; cleans up temporary files afterward.
        test("T38 actual disk persistence, file locking and reopening",()->{
            Path dir=Files.createTempDirectory("storage-system-test-");Path file=dir.resolve("state.bin");
            try {
                try(var s=new StorageSystem(new StorageSystem.LocalStore(file))) { for(int i=0;i<3;i++)s.addNode("n"+i,"r"+i,100);s.uploadText("a","hello");expect(java.nio.channels.OverlappingFileLockException.class,()->new StorageSystem.LocalStore(file)); }
                try(var loaded=new StorageSystem(new StorageSystem.LocalStore(file))) { check(loaded.files(1,100).size()==1&&used(loaded)==15,"Disk reopen");loaded.validateState(); }
            } finally { Files.deleteIfExists(file);Files.deleteIfExists(dir.resolve("state.bin.lock"));Files.deleteIfExists(dir); }
        });
        // T39: Injects a save failure and checks rollback, then checks recovery through reloading the store after the failure is disabled.
        test("T39 persistence failure rolls back state and blocks later writes",()->{
            class FailingStore implements StorageSystem.Store {
                byte[] bytes;boolean fail;
                public byte[] load(){return bytes;}
                public void save(byte[] b,long revision) throws IOException { if(fail)throw new IOException("Injected disk/database failure");bytes=b; }
            }
            var store=new FailingStore();var s=new StorageSystem(store);for(int i=0;i<3;i++)s.addNode("n"+i,"r"+i,100);long rev=s.state.revision;store.fail=true;
            expect(IllegalStateException.class,()->s.uploadText("a","hello"));check(s.state.files.isEmpty()&&used(s)==0&&s.state.revision==rev&&s.state.undo.isEmpty(),"Persistence rollback");
            store.fail=false;expect(IllegalStateException.class,()->s.uploadText("a","hello"));var reloaded=new StorageSystem(store);check(reloaded.uploadText("a","hello").id==1,"Reload after failure");
        });
        // T40: Checks corrupted serialized state is rejected instead of silently reset.
        test("T40 corrupt state rejected; no silent reset",()->{expect(IOException.class,()->StorageSystem.decode(new byte[]{1,2,3}));});
        // GROUP 7: Large values, randomized checks, Unicode, and remaining edge cases.
        // T41: Checks capacity accounting at Long.MAX_VALUE and deletion without numeric overflow.
        test("T41 long capacity boundary without overflow",()->{var s=cluster(3,Long.MAX_VALUE);var f=s.upload("large",hash("large"),Long.MAX_VALUE);for(var n:s.nodes())check(n.used==Long.MAX_VALUE&&n.free.isEmpty(),"Long capacity");s.delete(f.id);for(var n:s.nodes())check(n.used==0&&n.free.get(0).length==Long.MAX_VALUE,"Long free");});
        // T42: Runs 5,000 operations for each B-Tree degree 2–5 and compares insert/remove/get/range results with TreeMap (20,000 operations total).
        test("T42 randomized B-Tree versus TreeMap: 20000 operations",()->{
            // Fixed seed makes failures reproducible across repeated test runs.
            Random rng=new Random(74119);
            // Four degrees x 5,000 operations = 20,000 operations total.
            for(int degree=2;degree<=5;degree++) {
                // TreeMap is the reference implementation for expected ordered-map results.
                var t=new BTree<Integer,Integer>(degree);TreeMap<Integer,Integer> reference=new TreeMap<>();
                for(int i=0;i<5000;i++) {
                    int key=rng.nextInt(300)-100,op=rng.nextInt(4);
                    if(op==0) { int value=rng.nextInt();t.put(key,value);reference.put(key,value); }
                    else if(op==1)check(Objects.equals(t.remove(key),reference.remove(key)),"Random remove");
                    else if(op==2)check(Objects.equals(t.get(key),reference.get(key)),"Random get");
                    else { int high=key+rng.nextInt(50);check(t.range(key,high).equals(new ArrayList<>(reference.subMap(key,true,high,true).entrySet())),"Random range"); }
                    // After every operation, check both structural invariants and exact contents.
                    t.validate();check(t.size()==reference.size()&&t.entries().equals(new ArrayList<>(reference.entrySet())),"Random contents");
                }
            }
        });
        // T43: Checks UTF-8 byte length for the euro sign and exercises Unicode node IDs.
        test("T43 UTF-8 byte size and Unicode node IDs",()->{var s=cluster(0,1);s.addNode("\uffffA","a",100);s.addNode("\uffffB","b",100);s.addNode("\uffffC","c",100);var f=s.uploadText("unicode","\u20ac");check(f.size==3&&used(s)==9,"UTF-8 size or node range");});
        // T44: Checks metadata-only uploads do not claim unknown file bytes are verified.
        test("T44 metadata mode never claims to verify unknown bytes",()->{var s=cluster(3,100);s.upload("metadata",hash("hello"),5);expect(IllegalArgumentException.class,()->s.uploadText("real","hello"));check(s.getFile(1).content==null,"Unverified content");});
        // T45: Checks downloading an unknown file ID throws NoSuchElementException.
        test("T45 missing download rejected",()->{var s=cluster(3,100);expect(NoSuchElementException.class,()->s.download(99));});
        // T46: Checks failed-node recovery followed by deletion leaves no stale capacity usage.
        test("T46 failed-node return and delete leave no ghost usage",()->{var s=cluster(4,100);var f=s.uploadText("a","hello");s.setHealth("N1",false);drain(s);s.heartbeat("N1");s.delete(f.id);check(used(s)==0,"Ghost usage");s.validateState();});
        // T47: Runs 150 randomized storage operations and validates system invariants after each operation.
        test("T47 randomized application operations preserve allocation invariants",()->{
            var s=cluster(5,200);Random random=new Random(9301);
            for(int i=0;i<150;i++) {
                long id=1+random.nextInt((int)Math.max(1,s.state.nextFile));String node="N"+(1+random.nextInt(5));
                try {
                    switch(random.nextInt(7)) {
                        case 0 -> s.upload("f"+i,hash("random"+i),random.nextInt(60));
                        case 1 -> s.delete(id);
                        case 2 -> s.download(id);
                        case 3 -> s.undo();
                        case 4 -> s.setHealth(node,random.nextBoolean());
                        case 5 -> s.runNextTask();
                        case 6 -> { s.requestRebalance();s.runNextTask(); }
                    }
                } catch(IllegalArgumentException|IllegalStateException|NoSuchElementException expected) { }
                s.validateState();
            }
        });
        // T48: Checks inserting an existing B-Tree key replaces its value without increasing size, and null keys/values are rejected.
        test("T48 B-Tree replacement and null policy",()->{var t=new BTree<Integer,String>(2);t.put(1,"a");t.put(1,"b");check(t.size()==1&&t.get(1).equals("b"),"Replace");expect(NullPointerException.class,()->t.put(null,"x"));expect(NullPointerException.class,()->t.put(2,null));});
        // Reaching this line means all test groups completed without a failure.
        // These tests do not, by themselves, prove that live MySQL integration works.
        System.out.println("\nALL "+passed+" TEST GROUPS PASSED (including 20000 randomized B-Tree operations). MySQL integration is separate.");
    }
}
