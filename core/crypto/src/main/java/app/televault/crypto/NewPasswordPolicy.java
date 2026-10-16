package app.televault.crypto;

import java.security.SecureRandom;

/** Creation policy only. Historical credentials remain readable for migration. */
public final class NewPasswordPolicy {
    private NewPasswordPolicy() {}
    public static boolean accepts(CharSequence value) {
        if (value == null || value.length() < 12 || value.length() > 128 || PatternRecovery.decode(value.toString()) != null) return false;
        boolean upper=false, lower=false, digit=false, symbol=false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c>= 'A' && c<= 'Z')upper=true;
            else if(c>= 'a' && c<= 'z')lower=true;
            else if(c>= '0' && c<= '9')digit=true;
            else if(c>=33 && c<=126)symbol=true;
            else return false;
        }
        return upper && lower && digit && symbol;
    }
    public static boolean accepts(char[] value) {
        if(value==null)return false;
        return accepts(java.nio.CharBuffer.wrap(value));
    }
    public static String generate() {
        SecureRandom random=new SecureRandom();
        String[] groups={"ABCDEFGHJKLMNPQRSTUVWXYZ","abcdefghijkmnopqrstuvwxyz","23456789","@%_!#-=+"};
        String all=String.join("",groups);char[] result=new char[20];
        for(int i=0;i<groups.length;i++)result[i]=groups[i].charAt(random.nextInt(groups[i].length()));
        for(int i=groups.length;i<result.length;i++)result[i]=all.charAt(random.nextInt(all.length()));
        for(int i=result.length-1;i>0;i--){int j=random.nextInt(i+1);char c=result[i];result[i]=result[j];result[j]=c;}
        String password=new String(result);java.util.Arrays.fill(result,'\0');return password;
    }
}
