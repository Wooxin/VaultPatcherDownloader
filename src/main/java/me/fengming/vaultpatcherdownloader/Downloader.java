package me.fengming.vaultpatcherdownloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;

public class Downloader {
    public static String getString(URL index) throws IOException {
        StringBuilder contents = new StringBuilder();
        String s;
        URLConnection urlConnection = index.openConnection();
        urlConnection.setReadTimeout(3000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8));
        while ((s = reader.readLine()) != null) {
            contents.append(s);
        }
        return contents.toString();
    }
}
