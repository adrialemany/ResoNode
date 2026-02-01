package com.example.spotifly;

import android.util.Log;
import java.util.Properties;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.SubjectTerm;

public class UrlFetcher {

    public interface UrlCallback {
        void onUrlFound(String url);
        void onError(Exception e);
    }

    public static void fetchLatestUrl(UrlCallback callback) {
        new Thread(() -> {
            try {
                Properties props = new Properties();
                props.put("mail.store.protocol", "imaps");

                Session session = Session.getInstance(props, null);
                Store store = session.getStore("imaps");

                
                store.connect("imap.gmail.com", Config.GMAIL_EMAIL, Config.GMAIL_APP_PASSWORD);

                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);

                
                
                Message[] messages = inbox.search(new SubjectTerm("Enlace a Cloudfare"));

                if (messages.length > 0) {
                    
                    Message latestMessage = messages[messages.length - 1];

                    
                    Object content = latestMessage.getContent();
                    String newUrl = "";

                    if (content instanceof String) {
                        newUrl = (String) content;
                    } else if (content instanceof javax.mail.Multipart) {
                        
                        javax.mail.Multipart multipart = (javax.mail.Multipart) content;
                        newUrl = multipart.getBodyPart(0).getContent().toString();
                    }

                    
                    newUrl = newUrl.trim();

                    if (!newUrl.isEmpty() && newUrl.startsWith("http")) {
                        callback.onUrlFound(newUrl);
                    } else {
                        callback.onError(new Exception("El correo no contenía una URL válida"));
                    }
                } else {
                    callback.onError(new Exception("No se encontraron correos con ese asunto"));
                }

                inbox.close(false);
                store.close();

            } catch (Exception e) {
                e.printStackTrace();
                callback.onError(e);
            }
        }).start();
    }
}