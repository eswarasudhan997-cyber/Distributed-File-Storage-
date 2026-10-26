import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Java 17+. Interactive single-process storage control-plane simulation. */
public class StorageSystem implements AutoCloseable {
    static final int R = 3;


    static final class Extent implements Serializable {
        private static final long serialVersionUID=1L;
        long start,length;
        Extent(long start,long length) { this.start=start;this.length=length; }
        public String toString() { return "["+start+", "+(start+length)+")"; }
    }
    static final class StorageNode implements Serializable {
        private static final long serialVersionUID=1L;
        final String id,region;final long capacity;
        long used,heartbeat;boolean healthy=true;
        final ArrayList<Extent> free=new ArrayList<>();
        StorageNode(String id,String region,long capacity,long now) { this.id=id;this.region=region;this.capacity=capacity;heartbeat=now;free.add(new Extent(0,capacity)); }
        double load() { return (double)used/capacity; }
        public String toString() { return String.format(Locale.ROOT,"%s | region=%s | %s | used=%d/%d bytes | load=%.2f%% | heartbeat=%s | free=%s",id,region,healthy?"UP":"DOWN",used,capacity,load()*100,Instant.ofEpochMilli(heartbeat),free); }
    }
    static final class FileRecord implements Serializable {
        private static final long serialVersionUID=1L;
        final long id,size;final String name,hash;
        final byte[] content; // null for metadata-only uploads
        final LinkedHashMap<String,Long> replicas=new LinkedHashMap<>();
        long lastAccess;
        FileRecord(long id,String name,String hash,long size,byte[] content,long now) { this.id=id;this.name=name;this.hash=hash;this.size=size;this.content=content==null?null:content.clone();lastAccess=now; }
        public String toString() { return "F"+id+" | "+name+" | "+size+" bytes | SHA-256="+hash+" | replicas(node=offset)="+replicas+" | "+(content==null?"metadata only":"text content retained centrally"); }
    }
    static final class Task implements Comparable<Task>,Serializable {
        private static final long serialVersionUID=1L;
        final int priority;final long sequence,fileId;final String type;
        Task(int priority,long sequence,String type,long fileId) { this.priority=priority;this.sequence=sequence;this.type=type;this.fileId=fileId; }
        public int compareTo(Task o) { int c=Integer.compare(priority,o.priority);return c==0?Long.compare(sequence,o.sequence):c; }
        public String toString() { return "priority="+priority+" sequence="+sequence+" "+type+(fileId==0?"":" F"+fileId); }
    }
    static final class Operation implements Serializable {
        private static final long serialVersionUID=1L;
        final String type;final long fileId,oldAccess;final FileRecord snapshot;final LinkedHashMap<Long,Long> oldCache;
        Operation(String type,long fileId,long oldAccess,FileRecord snapshot,LinkedHashMap<Long,Long> oldCache) { this.type=type;this.fileId=fileId;this.oldAccess=oldAccess;this.snapshot=snapshot;this.oldCache=oldCache; }
    }
    static final class State implements Serializable {
        private static final long serialVersionUID=1L;
        final int format=1;
        long revision,nextFile=1,nextTask=1,cacheTick,timeoutMillis=300_000;
        double imbalanceThreshold=.25;int cacheLimit=5;
        final LinkedHashMap<Long,FileRecord> files=new LinkedHashMap<>();
        final LinkedHashMap<String,StorageNode> nodes=new LinkedHashMap<>();
        final LinkedHashMap<Long,Long> cache=new LinkedHashMap<>();
        final PriorityQueue<Task> tasks=new PriorityQueue<>();
        final ArrayDeque<Operation> undo=new ArrayDeque<>();
        final ArrayDeque<String> auditBuffer=new ArrayDeque<>();
        final ArrayList<String> audit=new ArrayList<>();
    }
    record FreeKey(long length,String node,long start) implements Comparable<FreeKey> {
        public int compareTo(FreeKey b) { int c=Long.compare(length,b.length);if(c==0)c=node.compareTo(b.node);return c==0?Long.compare(start,b.start):c; }
    }
    record LoadKey(double load,String node) implements Comparable<LoadKey> {
        public int compareTo(LoadKey b) { int c=Double.compare(load,b.load);return c==0?node.compareTo(b.node):c; }
    }
    record Download(FileRecord file,String node,boolean cacheHit) {}

    interface Store extends AutoCloseable {
        byte[] load() throws Exception;
        void save(byte[] bytes,long expectedRevision) throws Exception;
        default void close() throws Exception {}
    }
    static final class MemoryStore implements Store {
        byte[] bytes;
        public byte[] load() { return bytes==null?null:bytes.clone(); }
        public void save(byte[] next,long expected) { bytes=next.clone(); }
    }
    static final class LocalStore implements Store {
        final Path path;final java.nio.channels.FileChannel lockChannel;final java.nio.channels.FileLock lock;
        LocalStore(Path path) throws IOException {
            this.path=path.toAbsolutePath().normalize();Files.createDirectories(this.path.getParent());
            lockChannel=java.nio.channels.FileChannel.open(this.path.resolveSibling(this.path.getFileName()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try { lock=lockChannel.tryLock();if(lock==null)throw new IOException("State file is already open in another application"); }
            catch(IOException|RuntimeException ex) { lockChannel.close();throw ex; }
        }
        public byte[] load() throws IOException { return Files.exists(path)?Files.readAllBytes(path):null; }
        public void save(byte[] bytes,long expected) throws IOException {
            Path temp=Files.createTempFile(path.getParent(),"storage-",".tmp");
            try {
                try(java.nio.channels.FileChannel c=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)) { java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true); }
                Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        public void close() throws IOException { try { lock.release(); } finally { lockChannel.close(); } }
    }
    /** InnoDB transaction, row locking and revision checking; no external library to compile. */
    static final class MySqlStore implements Store {
        final Connection connection;
        MySqlStore(String url,String user,String password) throws SQLException {
            connection=DriverManager.getConnection(url,user,password);
            try(Statement s=connection.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS storage_app_state (id INT PRIMARY KEY, revision BIGINT NOT NULL, payload LONGBLOB NULL) ENGINE=InnoDB");
                s.executeUpdate("INSERT IGNORE INTO storage_app_state(id,revision,payload) VALUES(1,0,NULL)");
            } catch(SQLException ex) { connection.close();throw ex; }
        }
        public byte[] load() throws SQLException {
            try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT payload FROM storage_app_state WHERE id=1")) { if(!r.next())throw new SQLException("Missing application state row");return r.getBytes(1); }
        }
        public void save(byte[] bytes,long expected) throws SQLException {
            connection.setAutoCommit(false);
            try {
                try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT revision FROM storage_app_state WHERE id=1 FOR UPDATE")) { if(!r.next()||r.getLong(1)!=expected)throw new SQLException("Database changed in another session; restart to reload"); }
                try(PreparedStatement p=connection.prepareStatement("UPDATE storage_app_state SET revision=?,payload=? WHERE id=1")) { p.setLong(1,Math.addExact(expected,1));p.setBytes(2,bytes);p.executeUpdate(); }
                connection.commit();
            } catch(SQLException|RuntimeException ex) { try { connection.rollback(); } catch(SQLException rollback) { ex.addSuppressed(rollback); } throw ex; }
            finally { connection.setAutoCommit(true); }
        }
        public void close() throws SQLException { connection.close(); }
    }

    private final Store store;State state;private boolean persistenceFailed;
    // Six B-Tree indexes reconstructed from authoritative state on load/commit.
    private BTree<Long,FileRecord> primary;
    private BTree<String,Long> dedup;
    private BTree<FreeKey,Extent> freeBlocks;
    private BTree<String,StorageNode> nodeIndex;
    private BTree<LoadKey,StorageNode> loadIndex;
    private BTree<Long,FileRecord> cacheIndex;
    StorageSystem(Store store) throws Exception {
        this.store=store;byte[] stored=store.load();state=stored==null?new State():decode(stored);
        if(state.format!=1)throw new IOException("Unsupported state version");rebuildIndexes();validateState();
    }
    static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ObjectOutputStream o=new ObjectOutputStream(out)) { o.writeObject(state); } return out.toByteArray();
    }
    static State decode(byte[] bytes) throws IOException,ClassNotFoundException {
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(info -> {
                if(info.depth()>80||info.references()>1_000_000||info.arrayLength()>100_000_000||info.streamBytes()>100_000_000)return ObjectInputFilter.Status.REJECTED;
                Class<?> c=info.serialClass();if(c==null)return ObjectInputFilter.Status.UNDECIDED;while(c.isArray())c=c.getComponentType();String n=c.getName();
                return c.isPrimitive()||n.startsWith("StorageSystem$")||n.startsWith("java.util.")||n.startsWith("java.lang.")?ObjectInputFilter.Status.ALLOWED:ObjectInputFilter.Status.REJECTED;
            });
            Object o=in.readObject();if(!(o instanceof State))throw new IOException("Invalid storage state");return (State)o;
        }
    }
    private void rebuildIndexes() {
        primary=new BTree<>(3);dedup=new BTree<>(3);freeBlocks=new BTree<>(3);nodeIndex=new BTree<>(3);loadIndex=new BTree<>(3);cacheIndex=new BTree<>(3);
        for(FileRecord f:state.files.values()) { primary.put(f.id,f);dedup.put(f.hash,f.id); }
        for(StorageNode n:state.nodes.values()) { nodeIndex.put(n.id,n);loadIndex.put(new LoadKey(n.load(),n.id),n);for(Extent e:n.free)freeBlocks.put(new FreeKey(e.length,n.id,e.start),e); }
        state.cache.forEach((id,tick) -> { FileRecord f=state.files.get(id);if(f!=null)cacheIndex.put(id,f); });
    }
    private synchronized <T> T transaction(Supplier<T> action) {
        if(persistenceFailed)throw new IllegalStateException("Persistence failed earlier. Restart to reload authoritative state.");
        byte[] before;try { before=encode(state); } catch(IOException ex) { throw new IllegalStateException("Cannot snapshot state",ex); }
        boolean saving=false;
        try {
            T result=action.get();flushAudit();rebuildIndexes();validateState();long expected=state.revision;state.revision=Math.addExact(expected,1);
            byte[] next=encode(state);if(next.length>90_000_000)throw new IllegalStateException("Demo state exceeds 90 MB limit");saving=true;store.save(next,expected);return result;
        } catch(Exception ex) {
            if(saving)persistenceFailed=true; // A lost commit acknowledgement may be ambiguous: force a reload.
            try { state=decode(before);rebuildIndexes(); } catch(Exception restore) { ex.addSuppressed(restore); }
            if(ex instanceof RuntimeException)throw (RuntimeException)ex;
            throw new IllegalStateException("Save was not confirmed; restart before continuing. "+ex.getMessage(),ex);
        }
    }
    private static String required(String value,String label) { if(value==null||value.isBlank())throw new IllegalArgumentException(label+" is required");return value.trim(); }
    static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private void log(String message) { state.auditBuffer.add(Instant.now()+" "+message); }
    private void flushAudit() { while(!state.auditBuffer.isEmpty())state.audit.add(state.auditBuffer.remove()); }
    private StorageNode requireNode(String id) { StorageNode n=nodeIndex.get(required(id,"Node ID"));if(n==null)throw new IllegalArgumentException("Unknown node: "+id);return n; }
    private FileRecord requireFile(long id) { FileRecord f=primary.get(id);if(f==null)throw new NoSuchElementException("File not found: F"+id);return f; }
    void addNode(String id,String region,long capacity) {
        String nid=required(id,"Node ID"),r=required(region,"Region");if(capacity<=0)throw new IllegalArgumentException("Node capacity must be positive");
        transaction(() -> { if(nodeIndex.get(nid)!=null)throw new IllegalArgumentException("Duplicate node ID: "+nid);state.nodes.put(nid,new StorageNode(nid,r,capacity,System.currentTimeMillis()));log("NODE_ADD "+nid);return null; });
    }
    void configure(long timeoutMillis,double threshold,int cacheLimit) {
        if(timeoutMillis<=0||!Double.isFinite(threshold)||threshold<0||threshold>1||cacheLimit<0||cacheLimit>10000)throw new IllegalArgumentException("Timeout > 0, threshold 0..1, cache entries 0..10000 required");
        transaction(() -> { state.timeoutMillis=timeoutMillis;state.imbalanceThreshold=threshold;state.cacheLimit=cacheLimit;trimCache();log("CONFIG_UPDATED");return null; });
    }
    FileRecord uploadText(String name,String text) {
        Objects.requireNonNull(text,"text");byte[] bytes=text.getBytes(StandardCharsets.UTF_8);if(bytes.length>1_000_000)throw new IllegalArgumentException("Text is limited to 1 MB in this demo");
        return upload(name,sha256(bytes),bytes.length,bytes);
    }
    FileRecord upload(String name,String hash,long size) { return upload(name,hash,size,null); }
    private FileRecord upload(String name,String hash,long size,byte[] content) {
        String filename=required(name,"Filename"),h=required(hash,"SHA-256 hash").toLowerCase(Locale.ROOT);
        if(!h.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("SHA-256 requires 64 hexadecimal characters (or choose text upload)");
        if(size<0)throw new IllegalArgumentException("File size cannot be negative");
        return transaction(() -> {
            Long existing=dedup.get(h);
            if(existing!=null) {
                FileRecord f=requireFile(existing);
                if(f.size!=size||(content!=null&&f.content!=null&&!Arrays.equals(content,f.content)))throw new IllegalArgumentException("Hash collision or inconsistent content/size");
                if(content!=null&&f.content==null)throw new IllegalArgumentException("Existing metadata-only hash cannot be verified as text. Delete its record first.");
                state.undo.push(new Operation("NOOP",f.id,0,null,null));log("UPLOAD_DEDUP "+filename+" -> F"+f.id);return f;
            }
            List<StorageNode> targets=selectNodes(size,Set.of(),Set.of(),R);
            if(targets.size()<R)throw new IllegalStateException("Upload needs 3 distinct UP nodes, each with a contiguous free block of at least "+size+" bytes");
            long id=state.nextFile;state.nextFile=Math.addExact(id,1);FileRecord f=new FileRecord(id,filename,h,size,content,System.currentTimeMillis());
            for(StorageNode n:targets)f.replicas.put(n.id,allocate(n,size));state.files.put(id,f);state.undo.push(new Operation("UPLOAD",id,0,null,null));
            enqueue(5,"VERIFY",id);log("UPLOAD F"+id+" replicas="+f.replicas.keySet());return f;
        });
    }
    private List<StorageNode> selectNodes(long size,Set<String> excluded,Set<String> existingRegions,int limit) {
        Set<String> fits=new HashSet<>();
        if(size==0)fits.addAll(state.nodes.keySet());
        else { var last=freeBlocks.lastEntry();if(last!=null)for(var e:freeBlocks.range(new FreeKey(size,"",0),last.getKey()))fits.add(e.getKey().node()); }
        List<StorageNode> candidates=new ArrayList<>(),result=new ArrayList<>();
        for(var e:loadIndex.entries()) { StorageNode n=e.getValue();if(n.healthy&&fits.contains(n.id)&&!excluded.contains(n.id))candidates.add(n); }
        Set<String> regions=new HashSet<>(existingRegions);
        for(StorageNode n:candidates)if(result.size()<limit&&regions.add(n.region))result.add(n);
        for(StorageNode n:candidates)if(result.size()<limit&&!result.contains(n))result.add(n);return result;
    }
    private long allocate(StorageNode n,long size) {
        if(size==0)return 0;
        for(int i=0;i<n.free.size();i++) { Extent e=n.free.get(i);if(e.length>=size) { long start=e.start;e.start+=size;e.length-=size;if(e.length==0)n.free.remove(i);n.used=Math.addExact(n.used,size);return start; } }
        throw new IllegalStateException("No contiguous free block on "+n.id);
    }
    private void release(StorageNode n,long start,long size) {
        if(size==0)return;n.used-=size;n.free.add(new Extent(start,size));n.free.sort(Comparator.comparingLong(e->e.start));
        for(int i=1;i<n.free.size();) { Extent a=n.free.get(i-1),b=n.free.get(i);if(a.start+a.length==b.start) { a.length+=b.length;n.free.remove(i); } else i++; }
    }
    private void touchCache(long id) { state.cache.remove(id);state.cache.put(id,++state.cacheTick);trimCache(); }
    private void trimCache() { while(state.cache.size()>state.cacheLimit)state.cache.remove(state.cache.keySet().iterator().next()); }
    Download download(long id) {
        return transaction(() -> {
            FileRecord cached=cacheIndex.get(id);FileRecord f=cached==null?requireFile(id):cached;StorageNode best=null;
            for(String nid:f.replicas.keySet()) { StorageNode n=requireNode(nid);if(n.healthy&&(best==null||n.load()<best.load()))best=n; }
            if(best==null)throw new IllegalStateException("No healthy replica is available for F"+id);boolean hit=cacheIndex.get(id)!=null;
            state.undo.push(new Operation("DOWNLOAD",id,f.lastAccess,null,new LinkedHashMap<>(state.cache)));f.lastAccess=System.currentTimeMillis();touchCache(id);
            log("DOWNLOAD F"+id+" from="+best.id+" cache="+(hit?"HIT":"MISS"));return new Download(f,best.id,hit);
        });
    }
    boolean delete(long id) {
        return transaction(() -> {
            FileRecord f=primary.get(id);if(f==null) { log("DELETE_MISSING F"+id);return false; }
            state.undo.push(new Operation("DELETE",id,0,copyFile(f),new LinkedHashMap<>(state.cache)));removeFile(f);log("DELETE F"+id);return true;
        });
    }
    private static FileRecord copyFile(FileRecord f) { FileRecord c=new FileRecord(f.id,f.name,f.hash,f.size,f.content,f.lastAccess);c.replicas.putAll(f.replicas);return c; }
    private void removeFile(FileRecord f) {
        for(var e:f.replicas.entrySet())release(requireNode(e.getKey()),e.getValue(),f.size);
        state.files.remove(f.id);state.cache.remove(f.id);state.tasks.removeIf(t->t.fileId==f.id);
    }
    boolean undo() {
        return transaction(() -> {
            Operation op=state.undo.peek();if(op==null)return false;
            switch(op.type) {
                case "UPLOAD" -> { FileRecord f=primary.get(op.fileId);if(f!=null)removeFile(f); }
                case "DELETE" -> {
                    FileRecord f=copyFile(op.snapshot);
                    if(primary.get(f.id)!=null||dedup.get(f.hash)!=null)throw new IllegalStateException("Undo conflict: file ID or content exists");
                    List<StorageNode> targets=selectNodes(f.size,Set.of(),Set.of(),R);
                    if(targets.size()<R)throw new IllegalStateException("Cannot undo delete: need 3 healthy nodes with enough contiguous space. Undo remains pending.");
                    f.replicas.clear();for(StorageNode n:targets)f.replicas.put(n.id,allocate(n,f.size));state.files.put(f.id,f);restoreCache(op.oldCache);enqueue(5,"VERIFY",f.id);
                }
                case "DOWNLOAD" -> { FileRecord f=primary.get(op.fileId);if(f!=null)f.lastAccess=op.oldAccess;restoreCache(op.oldCache); }
                case "NOOP" -> { }
                default -> throw new IllegalStateException("Unknown undo entry");
            }
            state.undo.pop();log("UNDO_"+op.type+" F"+op.fileId);return true;
        });
    }
    private void restoreCache(Map<Long,Long> previous) { state.cache.clear();for(var e:previous.entrySet())if(state.files.containsKey(e.getKey()))state.cache.put(e.getKey(),e.getValue());trimCache(); }
    void setHealth(String id,boolean healthy) {
        transaction(() -> { StorageNode n=requireNode(id);n.healthy=healthy;if(healthy)n.heartbeat=System.currentTimeMillis();log("NODE_HEALTH "+n.id+" "+healthy);scheduleRepairs();return null; });
    }
    void heartbeat(String id) { setHealth(id,true); }
    private int healthyCount(FileRecord f) { int count=0;for(String id:f.replicas.keySet())if(state.nodes.get(id).healthy)count++;return count; }
    void checkHealth(long now) {
        transaction(() -> {
            for(StorageNode n:state.nodes.values())if(n.healthy&&now>n.heartbeat&&now-n.heartbeat>state.timeoutMillis) { n.healthy=false;log("HEARTBEAT_TIMEOUT "+n.id); }
            scheduleRepairs();double min=1,max=0;int healthy=0;
            for(StorageNode n:state.nodes.values())if(n.healthy) { healthy++;min=Math.min(min,n.load());max=Math.max(max,n.load()); }
            if(healthy>1&&max-min>state.imbalanceThreshold)enqueue(10,"REBALANCE",0);log("HEALTH_CHECK completed");return null;
        });
    }
    private void scheduleRepairs() { for(FileRecord f:state.files.values())if(healthyCount(f)<R)enqueue(1,"REPAIR",f.id); }
    private void enqueue(int priority,String type,long fileId) {
        for(Task t:state.tasks)if(t.type.equals(type)&&t.fileId==fileId)return;
        long seq=state.nextTask;state.nextTask=Math.addExact(seq,1);state.tasks.add(new Task(priority,seq,type,fileId));
    }
    void requestRebalance() { transaction(() -> { enqueue(10,"REBALANCE",0);log("REBALANCE_QUEUED");return null; }); }
    String runNextTask() {
        return transaction(() -> {
            Task t=state.tasks.poll();if(t==null)return "No pending tasks.";String result;
            if(t.type.equals("REBALANCE"))result=rebalance();
            else { FileRecord f=primary.get(t.fileId);if(f==null)result="Skipped task for deleted file";else if(t.type.equals("REPAIR"))result=repair(f);else result="Verified F"+f.id+": "+healthyCount(f)+"/3 healthy replicas"; }
            log("TASK_DONE "+t+" | "+result);return result;
        });
    }
    private String repair(FileRecord f) {
        int healthy=healthyCount(f);if(healthy>=R)return "F"+f.id+" already has 3 healthy replicas";
        if(healthy==0) { log("UNDER_REPLICATED F"+f.id+" no healthy source; placements retained");return "F"+f.id+": recovery blocked; no healthy source"; }
        Set<String> regions=new HashSet<>();for(String id:f.replicas.keySet())if(state.nodes.get(id).healthy)regions.add(state.nodes.get(id).region);
        List<StorageNode> targets=selectNodes(f.size,f.replicas.keySet(),regions,R-healthy);
        for(StorageNode target:targets) {
            String failed=null;for(String id:f.replicas.keySet())if(!state.nodes.get(id).healthy) { failed=id;break; }
            long start=allocate(target,f.size);
            // Release is immediate in this logical simulation; no physical server is contacted.
            if(failed!=null) { long old=f.replicas.remove(failed);release(requireNode(failed),old,f.size); }
            f.replicas.put(target.id,start);log("REPLICATE F"+f.id+" -> "+target.id);healthy++;
        }
        if(healthy<R)log("UNDER_REPLICATED F"+f.id+" healthy="+healthy);return "F"+f.id+": "+healthy+"/3 healthy replicas";
    }
    private String rebalance() {
        int moves=0;
        while(moves<1000) {
            rebuildIndexes();List<StorageNode> healthy=new ArrayList<>();for(var e:loadIndex.entries())if(e.getValue().healthy)healthy.add(e.getValue());
            if(healthy.size()<2||healthy.get(healthy.size()-1).load()-healthy.get(0).load()<=state.imbalanceThreshold)break;
            FileRecord chosen=null;StorageNode from=null,to=null;double bestGain=1e-12;
            for(FileRecord f:state.files.values())if(f.size>0&&healthyCount(f)==R)for(String id:f.replicas.keySet()) {
                StorageNode a=requireNode(id);
                for(StorageNode b:healthy)if(!f.replicas.containsKey(b.id)&&canFit(b,f.size)&&regionCountAfter(f,id,b)>=regionCount(f)) {
                    double before=a.load()*a.load()+b.load()*b.load();double al=(double)(a.used-f.size)/a.capacity,bl=(double)(b.used+f.size)/b.capacity;double gain=before-al*al-bl*bl;
                    if(gain>bestGain) { bestGain=gain;chosen=f;from=a;to=b; }
                }
            }
            if(chosen==null)break;
            long newStart=allocate(to,chosen.size),oldStart=chosen.replicas.remove(from.id);chosen.replicas.put(to.id,newStart);release(from,oldStart,chosen.size);log("MIGRATE F"+chosen.id+" "+from.id+" -> "+to.id);moves++;
        }
        return "Rebalance completed: "+moves+" replica migrations; placement, region and capacity constraints may prevent further improvement";
    }
    private boolean canFit(StorageNode n,long size) { if(!n.healthy)return false;if(size==0)return true;for(Extent e:n.free)if(e.length>=size)return true;return false; }
    private int regionCount(FileRecord f) { Set<String> regions=new HashSet<>();for(String id:f.replicas.keySet())regions.add(state.nodes.get(id).region);return regions.size(); }
    private int regionCountAfter(FileRecord f,String removed,StorageNode added) { Set<String> regions=new HashSet<>();for(String id:f.replicas.keySet())if(!id.equals(removed))regions.add(state.nodes.get(id).region);regions.add(added.region);return regions.size(); }
    List<FileRecord> files(long low,long high) { return primary.range(low,high).stream().map(Map.Entry::getValue).toList(); }
    List<StorageNode> nodes() { return nodeIndex.entries().stream().map(Map.Entry::getValue).toList(); }
    List<Task> tasks() { ArrayList<Task> list=new ArrayList<>(state.tasks);Collections.sort(list);return list; }
    List<String> audit() { return List.copyOf(state.audit); }
    FileRecord getFile(long id) { return primary.get(id); }
    String indexes() { return "Six B-Tree indexes: primary="+primary.size()+", dedup="+dedup.size()+", free-block="+freeBlocks.size()+", node="+nodeIndex.size()+", load="+loadIndex.size()+", cache="+cacheIndex.size()+"\nCache LRU -> MRU: "+state.cache.keySet()+" | undo entries="+state.undo.size()+" | tasks="+state.tasks.size()+" | revision="+state.revision; }
    void validateState() {
        primary.validate();dedup.validate();freeBlocks.validate();nodeIndex.validate();loadIndex.validate();cacheIndex.validate();
        if(primary.size()!=state.files.size()||dedup.size()!=state.files.size()||nodeIndex.size()!=state.nodes.size()||loadIndex.size()!=state.nodes.size()||cacheIndex.size()!=state.cache.size())throw new IllegalStateException("Index count mismatch");
        Map<String,List<Extent>> allocated=new HashMap<>();long maxId=0;int freeCount=0;
        for(FileRecord f:state.files.values()) {
            maxId=Math.max(maxId,f.id);
            if(f.id<=0||f.size<0||f.name.isBlank()||!f.hash.matches("[0-9a-f]{64}")||!Objects.equals(dedup.get(f.hash),f.id)||f.replicas.size()!=R)throw new IllegalStateException("Invalid file record");
            if(f.content!=null&&(f.content.length!=f.size||!sha256(f.content).equals(f.hash)))throw new IllegalStateException("Content checksum mismatch");
            for(var e:f.replicas.entrySet()) {
                StorageNode n=state.nodes.get(e.getKey());long offset=e.getValue();
                if(n==null||offset<0||offset>n.capacity||f.size>n.capacity-offset)throw new IllegalStateException("Invalid replica allocation");
                if(f.size>0)allocated.computeIfAbsent(n.id,k->new ArrayList<>()).add(new Extent(offset,f.size));
            }
        }
        if(state.nextFile<=maxId)throw new IllegalStateException("Invalid next file ID");
        for(StorageNode n:state.nodes.values()) {
            if(n.capacity<=0||n.used<0||n.used>n.capacity)throw new IllegalStateException("Invalid node capacity");
            List<Extent> pieces=new ArrayList<>(allocated.getOrDefault(n.id,List.of()));long used=0;for(Extent e:pieces)used=Math.addExact(used,e.length);
            if(used!=n.used)throw new IllegalStateException("Capacity accounting mismatch on "+n.id);
            pieces.addAll(n.free);freeCount+=n.free.size();pieces.sort(Comparator.comparingLong(e->e.start));long end=0;
            for(Extent e:pieces) { if(e.length<=0||e.start!=end||e.length>n.capacity-end)throw new IllegalStateException("Overlapping or missing extent on "+n.id);end+=e.length; }
            if(end!=n.capacity)throw new IllegalStateException("Unaccounted node space");
        }
        if(freeBlocks.size()!=freeCount||state.cache.size()>state.cacheLimit)throw new IllegalStateException("Free/cache count mismatch");
        for(long id:state.cache.keySet())if(!state.files.containsKey(id))throw new IllegalStateException("Stale cache entry");
    }
    public void close() throws Exception { store.close(); }

    /** Line-based input avoids nextInt()/nextLine() newline bugs. */
    static final class ConsoleInput {
        final BufferedReader reader=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        String line(String prompt) throws IOException { System.out.print(prompt);String value=reader.readLine();if(value==null)throw new EOFException();return value; }
        long number(String prompt) throws IOException { while(true)try { return Long.parseLong(line(prompt).trim()); } catch(NumberFormatException ex) { System.out.println("Enter a whole number within the Java long range."); } }
        double decimal(String prompt) throws IOException { while(true)try { double d=Double.parseDouble(line(prompt).trim());if(!Double.isFinite(d))throw new NumberFormatException();return d; } catch(NumberFormatException ex) { System.out.println("Enter a finite decimal number."); } }
        long fileId(String prompt) throws IOException { while(true)try { String id=line(prompt).trim();if(id.startsWith("F")||id.startsWith("f"))id=id.substring(1);long n=Long.parseLong(id);if(n<=0)throw new NumberFormatException();return n; } catch(NumberFormatException ex) { System.out.println("Enter a positive file ID, for example F1 or 1."); } }
    }
    public static void main(String[] args) {
        ConsoleInput in=new ConsoleInput();
        try {
            System.out.println("DISTRIBUTED FILE STORAGE & REPLICATION - INTERACTIVE SIMULATION");
            System.out.println("1. Local durable state\n2. MySQL durable state\n3. In-memory session");Store selected;
            while(true) {
                long choice=in.number("Storage mode: ");
                if(choice==1) { String p=in.line("State file path [storage-state.bin]: ").trim();selected=new LocalStore(Path.of(p.isEmpty()?"storage-state.bin":p));break; }
                if(choice==2) {
                    String url=in.line("JDBC URL [jdbc:mysql://localhost:3306/storage_system]: ").trim();if(url.isEmpty())url="jdbc:mysql://localhost:3306/storage_system";
                    String user=in.line("MySQL username: ").trim(),password=System.getenv("STORAGE_DB_PASSWORD");
                    if(password==null) {
                        java.io.Console console=System.console();
                        if(console!=null) { char[] chars=console.readPassword("MySQL password: ");if(chars==null)throw new EOFException();password=new String(chars);Arrays.fill(chars,'\0'); }
                        else password=in.line("MySQL password (visible in this IDE; set STORAGE_DB_PASSWORD to avoid this prompt): ");
                    }
                    selected=new MySqlStore(url,user,password);break;
                }
                if(choice==3) { selected=new MemoryStore();break; }System.out.println("Choose 1, 2 or 3.");
            }
            try(Store store=selected) {
                StorageSystem app=new StorageSystem(store);
                System.out.println("Loaded "+app.state.files.size()+" files and "+app.state.nodes.size()+" nodes. Replication factor = 3.");
                System.out.println("Enter your own nodes first. Timeouts are evaluated when you run option 9.");app.menu(in);
            }
        } catch(EOFException ex) { System.out.println("\nInput ended. Completed operations were saved in the selected mode."); }
        catch(Exception ex) { System.err.println("Cannot start/continue: "+ex.getMessage());System.err.println("MySQL needs a configured database and Connector/J on the classpath. Local mode needs no external library."); }
    }
    private void menu(ConsoleInput in) throws IOException {
        while(true) {
            System.out.println("\n1 Add node    2 List nodes     3 Upload text      4 Upload metadata");
            System.out.println("5 List files  6 Download       7 Delete           8 Undo file operation");
            System.out.println("9 Health check                10 Set node health 11 Send heartbeat");
            System.out.println("12 Show tasks 13 Run next task 14 Run all tasks   15 Queue rebalance");
            System.out.println("16 Audit log  17 File ID range 18 Indexes/cache   19 Configure");
            System.out.println("20 B-Tree playground          0 Save/exit");long choice=in.number("Choose: ");
            try {
                if(choice==0) { System.out.println("Goodbye. Every completed change was saved in the selected mode.");return; }
                switch(choice>=1&&choice<=20?(int)choice:-1) {
                    case 1 -> { String id=in.line("Node ID: "),region=in.line("Region: ");long cap=in.number("Capacity in bytes: ");addNode(id,region,cap);System.out.println("Node added."); }
                    case 2 -> printRows(nodes());
                    case 3 -> { String name=in.line("Filename: "),content=in.line("Text (one line; empty line means zero bytes): ");System.out.println(uploadText(name,content)); }
                    case 4 -> { String name=in.line("Filename: ");long size=in.number("Size in bytes: ");String hash=in.line("SHA-256 (64 hex characters): ");System.out.println(upload(name,hash,size)); }
                    case 5 -> printRows(files(1,Long.MAX_VALUE));
                    case 6 -> { Download d=download(in.fileId("File ID: "));System.out.println(d.file()+"\nSelected node: "+d.node()+" | cache "+(d.cacheHit()?"HIT":"MISS"));System.out.println(d.file().content==null?"Metadata-only simulation: no content was uploaded.":"Content: "+new String(d.file().content,StandardCharsets.UTF_8)); }
                    case 7 -> System.out.println(delete(in.fileId("File ID: "))?"File deleted; space released. Undo available.":"File not found; nothing deleted.");
                    case 8 -> System.out.println(undo()?"Latest file operation undone.":"Undo stack is empty.");
                    case 9 -> { checkHealth(System.currentTimeMillis());System.out.println("Health checked; tasks: "+state.tasks.size()+". Use 13 or 14 to process them."); }
                    case 10 -> { String id=in.line("Node ID: "),health=in.line("Health (up/down): ").trim();if(!health.equalsIgnoreCase("up")&&!health.equalsIgnoreCase("down"))throw new IllegalArgumentException("Enter up or down");setHealth(id,health.equalsIgnoreCase("up"));System.out.println("Health updated; repairs queued when needed."); }
                    case 11 -> { heartbeat(in.line("Node ID: "));System.out.println("Heartbeat recorded; node is UP."); }
                    case 12 -> printRows(tasks());
                    case 13 -> System.out.println(runNextTask());
                    case 14 -> { int count=0;while(!state.tasks.isEmpty()) { System.out.println(runNextTask());count++; }System.out.println("Processed "+count+" tasks. Retry blocked repairs via health check after conditions improve."); }
                    case 15 -> { requestRebalance();System.out.println("Rebalance queued. Process tasks to migrate replicas."); }
                    case 16 -> printRows(audit());
                    case 17 -> { long low=in.fileId("First file ID: "),high=in.fileId("Last file ID: ");printRows(files(low,high)); }
                    case 18 -> System.out.println(indexes());
                    case 19 -> {
                        System.out.println("Current timeout="+state.timeoutMillis/1000.0+" seconds, imbalance="+state.imbalanceThreshold+", cache="+state.cacheLimit);
                        long seconds=in.number("Heartbeat timeout seconds: ");double threshold=in.decimal("Rebalance threshold (0..1): ");long cache=in.number("Cache entries (0..10000): ");
                        configure(Math.multiplyExact(seconds,1000),threshold,Math.toIntExact(cache));System.out.println("Configuration saved.");
                    }
                    case 20 -> playground(in);
                    default -> System.out.println("Choose 0..20.");
                }
            } catch(RuntimeException ex) { System.out.println("Operation rejected: "+ex.getMessage()); }
        }
    }
    private static void printRows(Collection<?> values) { if(values.isEmpty())System.out.println("(empty)");else values.forEach(System.out::println); }
    private static void playground(ConsoleInput in) throws IOException {
        BTree<Long,String> tree=new BTree<>(Math.toIntExact(in.number("Minimum degree (2..1024): ")));
        System.out.println("Temporary B-Tree; separate from application data.");
        while(true) {
            long choice=in.number("B-Tree: 1 Put, 2 Get, 3 Remove, 4 Range, 5 List, 0 Back: ");if(choice==0)return;
            if(choice==1)tree.put(in.number("Key: "),in.line("Value: "));
            else if(choice==2)System.out.println(tree.get(in.number("Key: ")));
            else if(choice==3)System.out.println("Removed: "+tree.remove(in.number("Key: ")));
            else if(choice==4)printRows(tree.range(in.number("Low: "),in.number("High: ")));
            else if(choice==5)printRows(tree.entries());else System.out.println("Choose 0..5.");
        }
    }
}
