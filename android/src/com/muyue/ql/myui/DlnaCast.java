package com.muyue.ql.myui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal DLNA/UPnP AVTransport caster: discover MediaRenderers and push a stream URL. */
public class DlnaCast {

    public static class Device {
        public String name;
        public String location; // device description xml url
        public String controlUrl;
    }

    public interface ScanCallback {
        void onDevice(Device d);
        void onDone(int count);
    }

    private static volatile boolean scanning = false;

    /** SSDP discovery of AVTransport-capable renderers (runs on background thread). */
    public static void scan(final ScanCallback cb) {
        if (scanning) return;
        scanning = true;
        new Thread(new Runnable() {
            public void run() {
                int found = 0;
                MulticastSocket ms = null;
                try {
                    byte[] msg = ("M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 3\r\n" +
                            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                            "USER-AGENT: youtu/1.0\r\n\r\n").getBytes("UTF-8");
                    ms = new MulticastSocket();
                    ms.setSoTimeout(500);
                    try {
                        ms.send(new DatagramPacket(msg, msg.length,
                                InetAddress.getByName("239.255.255.250"), 1900));
                    } catch (Throwable ignored) {}
                    // send twice for reliability
                    Thread.sleep(400);
                    try {
                        ms.send(new DatagramPacket(msg, msg.length,
                                InetAddress.getByName("239.255.255.250"), 1900));
                    } catch (Throwable ignored) {}
                    long deadline = System.currentTimeMillis() + 6000;
                    ArrayList<String> seen = new ArrayList<String>();
                    byte[] buf = new byte[2048];
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket dp = new DatagramPacket(buf, buf.length);
                        try {
                            ms.receive(dp);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        String resp = new String(dp.getData(), 0, dp.getLength(), "UTF-8");
                        if (!resp.toUpperCase().contains("200 OK")) continue;
                        String loc = header(resp, "LOCATION");
                        if (loc == null || seen.contains(loc)) continue;
                        seen.add(loc);
                        Device d = buildDevice(loc);
                        if (d != null) {
                            found++;
                            cb.onDevice(d);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (ms != null) try { ms.close(); } catch (Throwable ignored) {}
                    scanning = false;
                    final int n = found;
                    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                    h.post(new Runnable() { public void run() { cb.onDone(n); } });
                }
            }
        }).start();
    }

    private static String header(String resp, String name) {
        for (String line : resp.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name))
                return line.substring(c + 1).trim();
        }
        return null;
    }

    private static Device buildDevice(String locationUrl) {
        try {
            String xml = httpGet(locationUrl);
            Matcher m = Pattern.compile("<friendlyName>(.*?)</friendlyName>", Pattern.DOTALL).matcher(xml);
            String name = m.find() ? m.group(1).trim() : "未知设备";
            // find AVTransport service controlURL
            Matcher svc = Pattern.compile(
                    "<service>.*?</service>", Pattern.DOTALL).matcher(xml);
            String control = null;
            while (svc.find()) {
                String s = svc.group(0);
                if (s.contains("AVTransport")) {
                    Matcher cu = Pattern.compile("<controlURL>(.*?)</controlURL>").matcher(s);
                    if (cu.find()) { control = cu.group(1).trim(); break; }
                }
            }
            if (control == null) return null;
            Device d = new Device();
            d.name = name;
            d.location = locationUrl;
            d.controlUrl = resolveUrl(locationUrl, control);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String resolveUrl(String base, String path) {
        if (path.startsWith("http")) return path;
        try {
            URL u = new URL(new URL(base), path);
            return u.toString();
        } catch (Throwable t) {
            return path;
        }
    }

    /** push uri to renderer, return error msg or null on success */
    public static String cast(final Device d, final String uri) {
        try {
            String esc = uri.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String setBody = soapBody("SetAVTransportURI",
                    "<InstanceID>0</InstanceID><CurrentURI>" + esc + "</CurrentURI>" +
                    "<CurrentURIMetaData></CurrentURIMetaData>");
            String r1 = soapPost(d.controlUrl, "SetAVTransportURI", setBody);
            Thread.sleep(600);
            String playBody = soapBody("Play",
                    "<InstanceID>0</InstanceID><Speed>1</Speed>");
            String r2 = soapPost(d.controlUrl, "Play", playBody);
            if ((r1 != null && r1.contains("UPnPError")) || (r2 != null && r2.contains("UPnPError")))
                return "设备返回错误";
            return null;
        } catch (Throwable t) {
            return t.toString();
        }
    }

    private static String soapBody(String action, String args) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                args + "</u:" + action + "></s:Body></s:Envelope>";
    }

    private static String soapPost(String url, String action, String body) throws Exception {
        URL u = new URL(url);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), u.getPort() == -1 ? 80 : u.getPort()), 6000);
        s.setSoTimeout(6000);
        OutputStream os = s.getOutputStream();
        byte[] data = body.getBytes("UTF-8");
        String req = "POST " + u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "") + " HTTP/1.1\r\n" +
                "HOST: " + u.getHost() + ":" + (u.getPort() == -1 ? 80 : u.getPort()) + "\r\n" +
                "CONTENT-LENGTH: " + data.length + "\r\n" +
                "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n" +
                "SOAPACTION: \"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"\r\n" +
                "Connection: close\r\n\r\n";
        os.write(req.getBytes("UTF-8"));
        os.write(data);
        os.flush();
        InputStream is = s.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        s.close();
        return bos.toString("UTF-8");
    }

    private static String httpGet(String url) throws Exception {
        URL u = new URL(url);
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), u.getPort() == -1 ? 80 : u.getPort()), 5000);
        s.setSoTimeout(5000);
        OutputStream os = s.getOutputStream();
        String req = "GET " + u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "") + " HTTP/1.1\r\n" +
                "HOST: " + u.getHost() + "\r\nConnection: close\r\n\r\n";
        os.write(req.getBytes("UTF-8"));
        os.flush();
        InputStream is = s.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        s.close();
        String all = bos.toString("UTF-8");
        int i = all.indexOf("\r\n\r\n");
        return i >= 0 ? all.substring(i + 4) : all;
    }
}
