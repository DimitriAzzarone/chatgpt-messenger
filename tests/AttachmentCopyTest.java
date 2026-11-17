import com.dimitriazzarone.chatgptmessenger.DanAttachmentCopy;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public class AttachmentCopyTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        File folder=Files.createTempDirectory("dan-attachment-test").toFile();
        File file=new File(folder,"document.pdf");
        byte[] expected=new byte[170000];for(int i=0;i<expected.length;i++)expected[i]=(byte)i;
        check(DanAttachmentCopy.copy(new ByteArrayInputStream(expected),file,expected.length)==expected.length,"byte count");
        check(Arrays.equals(expected,Files.readAllBytes(file.toPath())),"content changed");
        File limited=new File(folder,"limited.pdf");
        try{DanAttachmentCopy.copy(new ByteArrayInputStream(expected),limited,1000);throw new AssertionError("limit ignored");}catch(IOException wanted){}
        check(!limited.exists()&&!new File(limited+".part").exists(),"partial file retained");
        File broken=new File(folder,"broken.pdf");
        InputStream failure=new InputStream(){int left=70000;public int read()throws IOException{if(--left<0)throw new IOException("provider error");return 1;}public int read(byte[] b,int off,int n)throws IOException{if(left<=0)throw new IOException("provider error");int size=Math.min(left,n);left-=size;Arrays.fill(b,off,off+size,(byte)1);return size;}};
        try{DanAttachmentCopy.copy(failure,broken,1000000);throw new AssertionError("provider error ignored");}catch(IOException wanted){}
        check(!broken.exists()&&!new File(broken+".part").exists(),"failed provider copy retained");
        final int size=32*1024*1024;
        InputStream generated=new InputStream(){int remaining=size;public int read(){return remaining-->0?7:-1;}public int read(byte[] b,int off,int n){check(n<=65536,"copy requested a large buffer");if(remaining==0)return -1;int count=Math.min(remaining,n);Arrays.fill(b,off,off+count,(byte)7);remaining-=count;return count;}};
        File big=new File(folder,"large.bin");check(DanAttachmentCopy.copy(generated,big,size)==size,"large stream count");check(big.length()==size,"large file truncated");
        file.delete();big.delete();folder.delete();
        System.out.println("Attachment copy: content, size limit, provider errors, cleanup and bounded-buffer large streams passed");
    }
}
