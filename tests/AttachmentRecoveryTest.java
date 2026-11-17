import com.dimitriazzarone.chatgptmessenger.DanAttachmentRecovery;
import java.util.*;
public class AttachmentRecoveryTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[]args){
  String page="https://chatgpt.com/c/original";
  check(DanAttachmentRecovery.samePage(page,page));
  check(!DanAttachmentRecovery.samePage(page,"https://chatgpt.com/c/other"));
  check(!DanAttachmentRecovery.samePage(page,"https://chatgpt.com.evil.test/c/original"));
  check(!DanAttachmentRecovery.allowedPage("http://chatgpt.com/c/original"));
  check(!DanAttachmentRecovery.allowedPage("https://chatgpt.com:444/c/original"));
  check(!DanAttachmentRecovery.allowedPage(null));
  check(!DanAttachmentRecovery.allowedPage("https://user@chatgpt.com/"));
  List<String> valid=DanAttachmentRecovery.documents(Arrays.asList("content://provider/document/42","file:///tmp/a.jpg","content://provider/document/42",null,"https://example.com/file","javascript:alert(1)","content:missing-provider"));
  check(valid.equals(Arrays.asList("content://provider/document/42","file:///tmp/a.jpg")));
  System.out.println("Recovery: same-chat boundaries, origin, schemes and deduplication passed");
 }
}
