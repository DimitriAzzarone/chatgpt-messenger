package com.dimitriazzarone.chatgptmessenger;

import java.net.URI;
import java.util.*;

/** Origin and document checks for process-death attachment recovery. */
public final class DanAttachmentRecovery {
    private DanAttachmentRecovery() {}
    public static boolean allowedPage(String url) {
        try {
            URI u=new URI(url);
            return "https".equalsIgnoreCase(u.getScheme()) && "chatgpt.com".equalsIgnoreCase(u.getHost())
                    && (u.getPort()==-1 || u.getPort()==443) && u.getUserInfo()==null;
        } catch (Exception e) { return false; }
    }
    public static boolean samePage(String expected,String actual) {
        return expected!=null && expected.equals(actual) && allowedPage(expected);
    }
    public static List<String> documents(List<String> values) {
        LinkedHashSet<String> out=new LinkedHashSet<>();
        for(String value:values)try{
            URI uri=new URI(value);
            if (("content".equals(uri.getScheme()) && uri.getAuthority()!=null)
                    || ("file".equals(uri.getScheme()) && uri.getPath()!=null && uri.getPath().startsWith("/"))) out.add(value);
        }catch(Exception ignored){}
        return new ArrayList<>(out);
    }
}
