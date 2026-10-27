import java.util.*;

/** Generic B-Tree with insertion, replacement, deletion, range queries, and structural validation. */
/** Generic B-Tree with standard borrow/merge deletion and pruned range queries. */
public final class BTree<K extends Comparable<K>, V> {
    private final int t;
    private int count;
    private Node root = new Node();
    private final class Node {
        final ArrayList<K> keys = new ArrayList<>();
        final ArrayList<V> values = new ArrayList<>();
        final ArrayList<Node> children = new ArrayList<>();
        boolean leaf() { return children.isEmpty(); }
    }
    public BTree(int degree) {
        if (degree < 2 || degree > 1024) throw new IllegalArgumentException("Degree must be 2..1024");
        t = degree;
    }
    private int lower(Node n, K k) {
        int l=0,h=n.keys.size();
        while(l<h) { int m=(l+h)>>>1; if(n.keys.get(m).compareTo(k)<0)l=m+1;else h=m; }
        return l;
    }
    public V get(K k) {
        Objects.requireNonNull(k,"key"); Node n=root;
        while(true) {
            int i=lower(n,k);
            if(i<n.keys.size() && n.keys.get(i).compareTo(k)==0)return n.values.get(i);
            if(n.leaf())return null; n=n.children.get(i);
        }
    }
    public void put(K k,V v) {
        Objects.requireNonNull(k,"key"); Objects.requireNonNull(v,"value"); Node n=root;
        while(true) {
            int i=lower(n,k);
            if(i<n.keys.size() && n.keys.get(i).compareTo(k)==0) { n.values.set(i,v); return; }
            if(n.leaf())break; n=n.children.get(i);
        }
        if(root.keys.size()==2*t-1) { Node p=new Node();p.children.add(root);split(p,0);root=p; }
        insert(root,k,v);count++;
    }
    private void split(Node p,int i) {
        Node a=p.children.get(i),b=new Node(); K k=a.keys.get(t-1);V v=a.values.get(t-1);
        b.keys.addAll(a.keys.subList(t,a.keys.size()));b.values.addAll(a.values.subList(t,a.values.size()));
        a.keys.subList(t-1,a.keys.size()).clear();a.values.subList(t-1,a.values.size()).clear();
        if(!a.leaf()) { b.children.addAll(a.children.subList(t,a.children.size()));a.children.subList(t,a.children.size()).clear(); }
        p.keys.add(i,k);p.values.add(i,v);p.children.add(i+1,b);
    }
    private void insert(Node n,K k,V v) {
        int i=lower(n,k);
        if(n.leaf()) { n.keys.add(i,k);n.values.add(i,v);return; }
        if(n.children.get(i).keys.size()==2*t-1) { split(n,i);if(k.compareTo(n.keys.get(i))>0)i++; }
        insert(n.children.get(i),k,v);
    }
    public V remove(K k) {
        V old=get(k);if(old==null)return null;delete(root,k);count--;
        if(root.keys.isEmpty()&&!root.leaf())root=root.children.get(0);return old;
    }
    private void delete(Node n,K k) {
        int i=lower(n,k);
        if(i<n.keys.size()&&n.keys.get(i).compareTo(k)==0) {
            if(n.leaf()) { n.keys.remove(i);n.values.remove(i);return; }
            Node left=n.children.get(i),right=n.children.get(i+1);
            if(left.keys.size()>=t) {
                Node p=left;while(!p.leaf())p=p.children.get(p.children.size()-1);
                K replacement=p.keys.get(p.keys.size()-1);n.keys.set(i,replacement);n.values.set(i,p.values.get(p.values.size()-1));delete(left,replacement);
            } else if(right.keys.size()>=t) {
                Node p=right;while(!p.leaf())p=p.children.get(0);
                K replacement=p.keys.get(0);n.keys.set(i,replacement);n.values.set(i,p.values.get(0));delete(right,replacement);
            } else { merge(n,i);delete(left,k); }
            return;
        }
        if(n.leaf())return;
        if(n.children.get(i).keys.size()==t-1) {
            if(i>0&&n.children.get(i-1).keys.size()>=t)borrowLeft(n,i);
            else if(i<n.keys.size()&&n.children.get(i+1).keys.size()>=t)borrowRight(n,i);
            else if(i<n.keys.size())merge(n,i);else { merge(n,i-1);i--; }
        }
        delete(n.children.get(i),k);
    }
    private void borrowLeft(Node p,int i) {
        Node c=p.children.get(i),s=p.children.get(i-1);
        c.keys.add(0,p.keys.get(i-1));c.values.add(0,p.values.get(i-1));
        p.keys.set(i-1,s.keys.remove(s.keys.size()-1));p.values.set(i-1,s.values.remove(s.values.size()-1));
        if(!s.leaf())c.children.add(0,s.children.remove(s.children.size()-1));
    }
    private void borrowRight(Node p,int i) {
        Node c=p.children.get(i),s=p.children.get(i+1);
        c.keys.add(p.keys.get(i));c.values.add(p.values.get(i));
        p.keys.set(i,s.keys.remove(0));p.values.set(i,s.values.remove(0));
        if(!s.leaf())c.children.add(s.children.remove(0));
    }
    private void merge(Node p,int i) {
        Node a=p.children.get(i),b=p.children.remove(i+1);
        a.keys.add(p.keys.remove(i));a.values.add(p.values.remove(i));
        a.keys.addAll(b.keys);a.values.addAll(b.values);a.children.addAll(b.children);
    }
    public List<Map.Entry<K,V>> range(K low,K high) {
        Objects.requireNonNull(low);Objects.requireNonNull(high);List<Map.Entry<K,V>> out=new ArrayList<>();
        if(low.compareTo(high)<=0)range(root,low,high,out);return out;
    }
    private void range(Node n,K low,K high,List<Map.Entry<K,V>> out) {
        int i=lower(n,low);if(!n.leaf())range(n.children.get(i),low,high,out);
        while(i<n.keys.size()&&n.keys.get(i).compareTo(high)<=0) {
            out.add(Map.entry(n.keys.get(i),n.values.get(i)));if(n.keys.get(i).compareTo(high)==0)break;
            if(!n.leaf())range(n.children.get(i+1),low,high,out);i++;
        }
    }
    public List<Map.Entry<K,V>> entries() { List<Map.Entry<K,V>> out=new ArrayList<>();walk(root,out);return out; }
    private void walk(Node n,List<Map.Entry<K,V>> out) {
        for(int i=0;i<n.keys.size();i++) { if(!n.leaf())walk(n.children.get(i),out);out.add(Map.entry(n.keys.get(i),n.values.get(i))); }
        if(!n.leaf())walk(n.children.get(n.keys.size()),out);
    }
    public Map.Entry<K,V> firstEntry() { Node n=root;while(!n.leaf())n=n.children.get(0);return n.keys.isEmpty()?null:Map.entry(n.keys.get(0),n.values.get(0)); }
    public Map.Entry<K,V> lastEntry() { Node n=root;while(!n.leaf())n=n.children.get(n.children.size()-1);int i=n.keys.size()-1;return i<0?null:Map.entry(n.keys.get(i),n.values.get(i)); }
    public int size() { return count; }
    public void validate() {
        int[] depth={-1},total={0};validate(root,null,null,0,depth,total);
        if(total[0]!=count)throw new AssertionError("B-Tree size mismatch");
    }
    private void validate(Node n,K min,K max,int depth,int[] leafDepth,int[] total) {
        if(n.keys.size()>2*t-1||(n!=root&&n.keys.size()<t-1)||n.keys.size()!=n.values.size())throw new AssertionError("B-Tree occupancy");
        if(n==root&&!n.leaf()&&n.keys.isEmpty())throw new AssertionError("Empty internal root");K previous=min;
        for(K k:n.keys) { if((previous!=null&&k.compareTo(previous)<=0)||(max!=null&&k.compareTo(max)>=0))throw new AssertionError("B-Tree order");previous=k; }
        total[0]+=n.keys.size();
        if(n.leaf()) { if(leafDepth[0]<0)leafDepth[0]=depth;if(leafDepth[0]!=depth)throw new AssertionError("Unequal leaf depths"); }
        else {
            if(n.children.size()!=n.keys.size()+1)throw new AssertionError("Child count");
            for(int i=0;i<n.children.size();i++)validate(n.children.get(i),i==0?min:n.keys.get(i-1),i==n.keys.size()?max:n.keys.get(i),depth+1,leafDepth,total);
        }
    }
    public static void main(String[] args) {
    Scanner sc = new Scanner(System.in);
    BTree<Integer, String> tree = new BTree<>(2);

    while (true) {
        System.out.println("\n===== B-TREE MENU =====");
        System.out.println("1. Insert");
        System.out.println("2. Search");
        System.out.println("3. Update");
        System.out.println("4. Delete");
        System.out.println("5. Display all entries");
        System.out.println("6. Range query");
        System.out.println("7. First entry");
        System.out.println("8. Last entry");
        System.out.println("9. Validate B-Tree");
        System.out.println("10. Display size");
        System.out.println("0. Exit");
        System.out.print("Enter your choice: ");

        int choice = sc.nextInt();
        sc.nextLine();

        try {
            switch (choice) {
                case 1: {
                    System.out.print("Enter key: ");
                    int key = sc.nextInt();
                    sc.nextLine();

                    System.out.print("Enter value: ");
                    String value = sc.nextLine();

                    tree.put(key, value);
                    System.out.println("Inserted successfully.");
                    break;
                }

                case 2: {
                    System.out.print("Enter key to search: ");
                    int key = sc.nextInt();

                    String value = tree.get(key);
                    System.out.println(value == null
                            ? "Key not found."
                            : "Value: " + value);
                    break;
                }

                case 3: {
                    System.out.print("Enter key to update: ");
                    int key = sc.nextInt();
                    sc.nextLine();

                    if (tree.get(key) == null) {
                        System.out.println("Key not found.");
                    } else {
                        System.out.print("Enter new value: ");
                        String value = sc.nextLine();
                        tree.put(key, value);
                        System.out.println("Updated successfully.");
                    }
                    break;
                }

                case 4: {
                    System.out.print("Enter key to delete: ");
                    int key = sc.nextInt();

                    String removed = tree.remove(key);
                    System.out.println(removed == null
                            ? "Key not found."
                            : "Deleted value: " + removed);
                    break;
                }

                case 5: {
                    System.out.println("Entries: " + tree.entries());
                    break;
                }

                case 6: {
                    System.out.print("Enter lower key: ");
                    int low = sc.nextInt();

                    System.out.print("Enter upper key: ");
                    int high = sc.nextInt();

                    System.out.println(
                            "Range result: " + tree.range(low, high));
                    break;
                }

                case 7: {
                    System.out.println("First entry: " + tree.firstEntry());
                    break;
                }

                case 8: {
                    System.out.println("Last entry: " + tree.lastEntry());
                    break;
                }

                case 9: {
                    tree.validate();
                    System.out.println("B-Tree is valid.");
                    break;
                }

                case 10: {
                    System.out.println("Tree size: " + tree.size());
                    break;
                }

                case 0: {
                    System.out.println("Exiting B-Tree program.");
                    sc.close();
                    return;
                }

                default:
                    System.out.println("Invalid choice. Try again.");
            }
        } catch (InputMismatchException e) {
            System.out.println("Invalid input. Enter an integer where required.");
            sc.nextLine();
        } catch (AssertionError e) {
            System.out.println("B-Tree validation failed: " + e.getMessage());
        }
    }
}
}
