package com.example.resonode;

import android.util.Log;
import java.util.Properties;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.SubjectTerm;

public class ServerDiscovery {

    public interface DiscoveryCallback {
        void onSuccess(String newUrl);
        void onFailure(Exception e);
    }

    public static void findServerUrl(final DiscoveryCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Properties props = new Properties();
                    props.setProperty("mail.store.protocol", "imaps");

                    Session session = Session.getInstance(props, null);
                    Store store = session.getStore("imaps");

                    store.connect("imap.gmail.com", Config.GMAIL_EMAIL, Config.GMAIL_APP_PASSWORD);

                    Folder inbox = store.getFolder("INBOX");
                    inbox.open(Folder.READ_ONLY);

                    Message[] messages = inbox.search(new SubjectTerm("ResoNode Public URL"));

                    if (messages.length > 0) {
                        Message latestMsg = messages[messages.length - 1];
                        Object content = latestMsg.getContent();
                        String body = content.toString().trim();

                        String newUrl = body.replaceAll("\\s+", "");

                        inbox.close(false);
                        store.close();

                        callback.onSuccess(newUrl);
                    } else {
                        inbox.close(false);
                        store.close();
                        callback.onFailure(new Exception("No s'ha trobat l'email"));
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    callback.onFailure(e);
                }
            }
        }).start();
    }
}