package com.netonline.switchconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.scene.control.Alert;
import javafx.scene.control.skin.CellSkinBase;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class TelnetTaskExecutor {
    private String login;
    private String password;
    private String defGW;
    private String vlan_mgmt;
    private int resetDelay;
    private String resetIp;
    private int afterReset;
    private MainController mainController;

    public TelnetTaskExecutor(MainController mainController) {
        this.mainController = mainController;
        loadConfig();
    }

    public static class Config {
        public String login;
        public String password;
        public int reset_delay;
        public String default_gw;
        public String default_ip;
        public String vlan_mgmt;
        public int after_reset;

        // Геттеры и сеттеры, если потребуется
    }

    public void loadConfig() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            File file = new File("src/config.json");

            // Загружаем конфигурацию
            Config config = mapper.readValue(file, Config.class);

            // Присваиваем значения полям
            login = config.login;
            password = config.password;
            resetDelay = config.reset_delay;
            defGW = config.default_gw;
            resetIp = config.default_ip;
            vlan_mgmt = config.vlan_mgmt;
            afterReset = config.after_reset;

            mainController.appendConfigTextArea("Конфигурация успешно загружена!");
        } catch (IOException e) {
            mainController.appendConfigTextArea("Ошибка загрузки config.json: " + e.getMessage());
        }
    }

    // Метод для выполнения всех задач
    public void executeTasks(List<MainController.ConfigEntry> tasks) {
        new Thread(() -> {
            for (MainController.ConfigEntry task : tasks) {
//                System.out.println("Выполняется задача: ID=" + task.getId() +
//                        ", IP=" + task.getIp() +
//                        ", Тип=" + task.getSwitchType() +
//                        ", Новый коммутатор=" + task.isNewSwitch());

                try {
                    executeTask(task); // Выполнение одной задачи
                } catch (Exception e) {
                    System.err.println("Ошибка выполнения задачи ID=" + task.getId() + ": " + e.getMessage());
                }
            }
            System.out.println("Все задачи завершены.");
        }).start();
    }

    private void step_one_reset(String ip, String login, String password, Integer reset_delay) throws Exception {
        // Этап 1: Авторизация и сброс
        TelnetClient telnetClient = new TelnetClient(ip, 23);
        telnetClient.login(login, password);
        telnetClient.sendCommand("reset system force_agree");
        // Принудительное разрывание соединения
        telnetClient.forceDisconnect(); // Принудительно закрываем соединения
        mainController.appendConfigTextArea("Коммутатор сброшен. Ожидание " + resetDelay + " секунд...");
        Thread.sleep(reset_delay * 1000);
    }

    private void step_two_config(String resetIp, String switchType, String snmp, String uplink, String ip, Integer afterReset) throws Exception {
        TelnetClient configClient = new TelnetClient(resetIp, 23);
        configClient.login(login, password);
        String config = generateConfig(ip, switchType, snmp, uplink);
        configClient.sendCommand(config);
        mainController.appendConfigTextArea("Заливаем конфиг. Ожидание " + afterReset + " секунд...");
        Thread.sleep(afterReset * 1000);
        configClient.disconnect();
    }

    private void step_three_set_gw(String ip, String login, String password, String defGW) throws Exception {
        TelnetClient configGW = new TelnetClient(ip, 23);
        configGW.login(login, password);
        mainController.appendConfigTextArea("Устанавливаем Шлюз: " + ip);
        configGW.sendCommand("create iproute default " + defGW + " 1");
        configGW.sendCommand("save");
        Thread.sleep(30 * 1000);
        configGW.disconnect();
    }

    private void executeTask(MainController.ConfigEntry entry) throws Exception {
        String ip = entry.getIp();
        String switchType = entry.getSwitchType();
        String snmp = entry.getSnmp();
        String uplink = entry.getUplink();
        boolean stateSW = entry.isNewSwitch();

        mainController.startPing(ip);
        mainController.appendConfigTextArea("Начинаем задачу для IP: " + ip);

        System.out.println("ID Task: " + entry.getId() +
                "\nState Device : " + stateSW +
                "\nDevice IP :" + ip +
                "\nSwitch Type: " + switchType + "\n");

        if (stateSW) {
            // Выполняем только step_two_config и step_three_set_gw
            mainController.appendConfigTextArea("Устройство новое. Пропускаем сброс.");
            System.out.println("Новое устройство: Пропускаем сброс.");
            step_two_config(resetIp, switchType, snmp, uplink, ip, afterReset);
            step_three_set_gw(ip, login, password, defGW);
        } else {
            // Выполняем все действия
            mainController.appendConfigTextArea("Устройство не новое. Выполняем полный процесс.");
            System.out.println("Старое устройство: Выполняем полный процесс.");
            step_one_reset(ip, login, password, resetDelay);
            step_two_config(resetIp, switchType, snmp, uplink, ip, afterReset);
            step_three_set_gw(ip, login, password, defGW);
        }

        mainController.appendConfigTextArea("Конфигурация для " + ip + " выполнена успешно.");
    }


    private String generateConfig(String ip, String switchType, String snmp, String uplink) {

        String template = mainController.getTemplate(switchType);
        if (template == null) {
            throw new IllegalArgumentException("Не найден шаблон для типа коммутатора: " + switchType);
        }
        return template.replace("{snmpTextField}", snmp)
                .replace("{uplinkTextField}", uplink)
                .replace("{ipTextField}", ip);
    }

    private void showErrorT(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait(); // Дождаться закрытия окна пользователем
    }
}
