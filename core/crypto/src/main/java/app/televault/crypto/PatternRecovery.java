package app.televault.crypto;

/** Portable pattern representation fed to the existing salted recovery KDF.
 * The public prefix adds no entropy; patterns are weaker than long passwords. */
public final class PatternRecovery {
    private PatternRecovery() {}
    private static final String PREFIX = "TGPhotosPattern/v1:";
    public static String decode(String value) {
        if (value == null || !value.startsWith(PREFIX)) return null;
        String nodes = value.substring(PREFIX.length());
        return valid(nodes) ? nodes : null;
    }
    public static String encode(String nodes) {
        if (!valid(nodes)) throw new IllegalArgumentException("Use four to nine unique pattern dots");
        return PREFIX + nodes;
    }
    public static boolean valid(String nodes) {
        if (nodes == null || nodes.length() < 4 || nodes.length() > 9) return false;
        int seen=0;
        for (char c : nodes.toCharArray()) {
            if (c < '1' || c > '9') return false;
            int bit = 1 << (c-'1');
            if ((seen & bit) != 0) return false;
            seen |= bit;
        }
        return true;
    }
    public static String append(String nodes, int dot) {
        if (dot < 1 || dot > 9 || nodes.indexOf((char)('0'+dot)) >= 0) return nodes;
        if (!nodes.isEmpty()) {
            int previous=nodes.charAt(nodes.length()-1)-'1', next=dot-1;
            int x1=previous%3, y1=previous/3, x2=next%3, y2=next/3;
            if ((x1+x2)%2==0 && (y1+y2)%2==0 && (Math.abs(x1-x2)==2 || Math.abs(y1-y2)==2)) {
                int middle=(y1+y2)/2*3+(x1+x2)/2+1;
                char c=(char)('0'+middle);
                if (nodes.indexOf(c)<0) nodes += c;
            }
        }
        return nodes+(char)('0'+dot);
    }
}
