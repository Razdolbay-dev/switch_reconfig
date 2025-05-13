package com.netonline.switchconfig;

import java.io.*;
import java.net.Socket;


public class TelnetClient {
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private BufferedReader reader;

    public TelnetClient(String host, int port) throws Exception {
        socket = new Socket(host, port);
        inputStream = socket.getInputStream();
        outputStream = socket.getOutputStream();
        reader = new BufferedReader(new InputStreamReader(inputStream));
    }

    public void login(String username, String password) throws Exception {
        sendCommand(username); // Отправляем логин
        sendCommand(password); // Отправляем пароль
        Thread.sleep(1000);   // Даем время на авторизацию
    }

    public String sendCommand(String command) throws Exception {
        outputStream.write((command + "\r\n").getBytes());
        outputStream.flush();
        Thread.sleep(500); // Небольшая задержка для выполнения команды
        return readResponse(); // Возвращаем ответ на команду
    }

    public String readResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        while (reader.ready()) {
            response.append((char) reader.read());
        }
        return response.toString();
    }

    public void disconnect() throws Exception {
        if (socket != null) socket.close();
        if (inputStream != null) inputStream.close();
        if (outputStream != null) outputStream.close();
        if (reader != null) reader.close();
    }

    public void forceDisconnect() {
        try {
            if (socket != null) socket.close();
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (reader != null) reader.close();
        } catch (IOException e) {
            System.err.println("Ошибка при принудительном разрыве соединения: " + e.getMessage());
        } finally {
            socket = null;
            inputStream = null;
            outputStream = null;
            reader = null;
        }
    }
}