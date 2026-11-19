package com.dimitriazzarone.chatgptmessenger;

import android.content.*;
import android.util.Base64;
import java.io.*;
import java.security.MessageDigest;

/** Only fixed, bundled setup scripts: no caller-supplied command or path. */
final class DanTermuxKit {
    static final String PERMISSION="com.termux.permission.RUN_COMMAND";
    static final String URL="http://127.0.0.1:8766/files";
    private static byte[] asset(Context context,String name)throws IOException{
        try(InputStream in=context.getAssets().open("termux/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>200000)throw new IOException("Kit troppo grande");}return out.toByteArray();
        }
    }
    private static String install(Context context,String name)throws Exception{
        byte[] data=asset(context,name);String encoded=Base64.encodeToString(data,Base64.NO_WRAP);
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        String dest="\"$HOME/.local/share/dan-files-15520/"+name+"\"";
        return "printf '%s' '"+encoded+"' | base64 -d > "+dest+".new\nprintf '%s  %s\\n' '"+hash+"' "+dest+".new | sha256sum -c - >/dev/null\nmv "+dest+".new "+dest+"\n";
    }
    static Intent prepare(Context context)throws Exception{
        String script="set -eu\numask 077\nmkdir -p \"$HOME/.local/share/dan-files-15520\"\n"+install(context,"files_bridge.py")+install(context,"setup.sh")+"bash \"$HOME/.local/share/dan-files-15520/setup.sh\"\n";
        Intent intent=new Intent("com.termux.RUN_COMMAND");intent.setClassName("com.termux","com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-lc",script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR","/data/data/com.termux/files/home");
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND",false);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION","0");return intent;
    }
}
