package com.netonline.switchconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;


public class MainController {

    private TelnetTaskExecutor telnetTaskExecutor;

    @FXML
    private Button startButton;
    // Добавил Check box
    @FXML
    private TableColumn<ConfigEntry, Boolean> switchState;

    @FXML
    private CheckBox deviceState;


    @FXML
    private TextField ipTextField, snmpTextField, uplinkTextField;
    @FXML
    private TextArea configTextArea, textAreaPingStatic, textAreaPingDynamic;
    @FXML
    private ChoiceBox<String> deviceChoiceBox;
    @FXML
    private TableView<ConfigEntry> configTable;
    @FXML
    private TableColumn<ConfigEntry, String> idColumn, hostColumn, ipColumn, switchColumn, snmpColumn, uplinkColumn;

    private ObservableList<ConfigEntry> tableData = FXCollections.observableArrayList();
    private int currentId = 1; // Для автоматической нумерации ID

    private List<Thread> pingThreads = new ArrayList<>();
    private AtomicBoolean stopPinging = new AtomicBoolean(false);

    // Хранение шаблонов конфигурации для разных устройств
    private Map<String, DeviceTemplate> templates;

    @FXML
    public void initialize() {
        // Привязка колонки к полю isNewSwitch
        switchState.setCellValueFactory(cellData -> cellData.getValue().isNewSwitchProperty());

        // Установка CheckBox в колонке
        switchState.setCellFactory(CheckBoxTableCell.forTableColumn(switchState));

        // Настройка колонок
        idColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getId()));
        hostColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getHost()));
        ipColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getIp()));
        switchColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getSwitchType()));
        snmpColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getSnmp()));
        uplinkColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUplink()));

        // Привязка данных к таблице
        configTable.setItems(tableData);

        // Обработчик выбора строки
        configTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                // Выполнить действия для выбранной строки
                selectRow(newSelection);
            } else {
                // Сброс, если строка не выбрана
                clearSelection();
            }

            // Настройка обработчика кнопки
            startButton.setOnAction(event -> onExecuteTasks());

        });


        // Загрузка шаблонов из JSON
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            templates = objectMapper.readValue(new File("src/templates_qinq.json"), new TypeReference<>() {
            });
        } catch (IOException e) {
            configTextArea.setText("Не удалось загрузить шаблоны: " + e.getMessage());
            return;
        }

        // Инициализация ChoiceBox
        deviceChoiceBox.getItems().addAll(templates.keySet());
        deviceChoiceBox.setValue(templates.keySet().iterator().next());

        try {
            telnetTaskExecutor = new TelnetTaskExecutor(this);
        } catch (Exception e) {
            configTextArea.appendText("Ошибка инициализации TelnetTaskExecutor: " + e.getMessage() + "\n");
        }

        setupIPValidation(ipTextField);
        setupSNMPValidation(snmpTextField);

    }

    public void generateConfigFromTable(String ip, String snmp, String uplink, String device) {
        // Проверка на заполненность полей
        if (ip.isEmpty() || snmp.isEmpty() || uplink.isEmpty()) {
            showError("Ошибка", "Не все поля заполнены", "Заполните все поля перед генерацией конфигурации.");
            return; // Прервать выполнение, если есть незаполненные поля
        }

        // Если все поля заполнены, выполнить генерацию конфигурации
        DeviceTemplate deviceTemplate = templates.get(device);

        if (deviceTemplate == null) {
            configTextArea.setText("Шаблон для устройства " + device + " не найден.");
            return;
        }

        // Замена значений в шаблоне
        String config = deviceTemplate.getTemplate()
                .replace("{snmpTextField}", snmp)
                .replace("{uplinkTextField}", uplink)
                .replace("{ipTextField}", ip);

        configTextArea.setText(config);
    }

    private void showError(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait(); // Дождаться закрытия окна пользователем
    }

    private void setupIPValidation(TextField textField) {
        // Регулярное выражение для ввода IP-адреса (допускает частичный ввод)
        String partialIPPattern = "^(25[0-5]|2[0-4]\\d|1\\d{0,2}|\\d{0,2})(\\.(25[0-5]|2[0-4]\\d|1\\d{0,2}|\\d{0,2})){0,3}$";
        Pattern ipPattern = Pattern.compile(partialIPPattern);

        // Ограничитель ввода (TextFormatter)
        UnaryOperator<TextFormatter.Change> ipFilter = change -> {
            String newText = change.getControlNewText();
            if (ipPattern.matcher(newText).matches()) {
                return change;
            }
            return null; // Отклонить изменения, если не совпадает с шаблоном
        };

        TextFormatter<String> textFormatter = new TextFormatter<>(ipFilter);
        textField.setTextFormatter(textFormatter);

        // Проверка полного IP-адреса при каждом изменении
        textField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (isValidIPAddress(newValue)) {
                textField.setStyle("-fx-border-color: green; -fx-border-width: 2px;");
            } else {
                textField.setStyle("-fx-border-color: red; -fx-border-width: 2px;");
            }
        });
    }

    private void setupSNMPValidation(TextField textField) {
        // Регулярное выражение: разрешены латиница, цифры и нижнее подчеркивание
        String allowedPattern = "^[a-zA-Z0-9_]*$";
        Pattern snmpPattern = Pattern.compile(allowedPattern);

        // Ограничитель ввода
        UnaryOperator<TextFormatter.Change> snmpFilter = change -> {
            String newText = change.getControlNewText();
            if (snmpPattern.matcher(newText).matches()) {
                return change;
            }
            return null; // Отклонить изменения, если не совпадает с шаблоном
        };

        // Применение TextFormatter
        TextFormatter<String> textFormatter = new TextFormatter<>(snmpFilter);
        textField.setTextFormatter(textFormatter);

        // Визуальная подсказка корректности
        textField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.isEmpty() || isValidSNMPText(newValue)) {
                textField.setStyle("-fx-border-color: green; -fx-border-width: 2px;");
            } else {
                textField.setStyle("-fx-border-color: red; -fx-border-width: 2px;");
            }
        });
    }

    private boolean isValidIPAddress(String text) {
        // Регулярное выражение для полного IP-адреса
        String fullIPPattern = "^(25[0-5]|2[0-4]\\d|1\\d{2}|\\d{1,2})\\."
                + "(25[0-5]|2[0-4]\\d|1\\d{2}|\\d{1,2})\\."
                + "(25[0-5]|2[0-4]\\d|1\\d{2}|\\d{1,2})\\."
                + "(25[0-5]|2[0-4]\\d|1\\d{2}|\\d{1,2})$";
        return text.matches(fullIPPattern);
    }

    private boolean isValidSNMPText(String text) {
        // Проверка текста на соответствие допустимым символам
        return text.matches("^[a-zA-Z0-9_]+$");
    }

    public void startPing(String ip) {
        clearSelection();
        String staticIp = "10.90.90.90";
        String dynamicIp = ip;

        if (dynamicIp.isEmpty()) {
            textAreaPingDynamic.setText("Введите IP-адрес.");
            return;
        }

        // Сбрасываем флаг завершения
        stopPinging.set(false);

        // Создаем и запускаем потоки для пинга
        Thread staticPingThread = new Thread(() -> executeContinuousPing(staticIp, textAreaPingStatic));
        Thread dynamicPingThread = new Thread(() -> executeContinuousPing(dynamicIp, textAreaPingDynamic));

        staticPingThread.setDaemon(true); // Потоки завершатся при закрытии приложения
        dynamicPingThread.setDaemon(true);

        // Сохраняем потоки в список
        pingThreads.add(staticPingThread);
        pingThreads.add(dynamicPingThread);

        staticPingThread.start();
        dynamicPingThread.start();
    }

    private void executeContinuousPing(String ip, TextArea textArea) {
        String pingOption = getPingOption();
        String command = "ping " + pingOption + " " + ip;

        try {
            Process process = Runtime.getRuntime().exec(command);

            //Определяем кодировку в зависимости от ОС
            String os = System.getProperty("os.name").toLowerCase();
            String charset = os.contains("win") ? "CP866" : "UTF-8";

            // Чтение вывода с учетом кодировки

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while (!stopPinging.get() && (line = reader.readLine()) != null) {
                    appendToTextArea(textArea, line);
                }
            }
            process.destroy(); // Завершаем процесс, если флаг установлен
        } catch (Exception e) {
            appendToTextArea(textArea, "Ошибка при выполнении команды: " + e.getMessage());
        }
    }

    private String getPingOption() {
        // Опция для непрерывного пинга зависит от ОС
        String os = System.getProperty("os.name").toLowerCase();
        return os.contains("win") ? "-t" : "";
    }

    private void appendToTextArea(TextArea textArea, String message) {
        Platform.runLater(() -> textArea.appendText(message + "\n"));
    }

    private void clearSelection() {
        // Устанавливаем флаг завершения для всех потоков
        stopPinging.set(true);

        // Очищаем текстовые области
        //configTextArea.clear();
        textAreaPingStatic.clear();
        textAreaPingDynamic.clear();

        // Ждем завершения всех потоков
        for (Thread thread : pingThreads) {
            try {
                thread.join(); // Ожидание завершения потока
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Очищаем список потоков
        pingThreads.clear();
    }

    @FXML
    public void addConfigToTable() {
        // Проверить заполненность полей
        if (ipTextField.getText().isEmpty() || snmpTextField.getText().isEmpty() || uplinkTextField.getText().isEmpty()) {
            showError("Ошибка", "Не все поля заполнены", "Заполните все поля перед добавлением записи.");
            return;
        }

        // Создать новую запись
        ConfigEntry entry = new ConfigEntry(
                String.valueOf(currentId++), // ID
                "-", // Host
                ipTextField.getText(),
                deviceChoiceBox.getValue(),
                snmpTextField.getText(),
                uplinkTextField.getText(),
                deviceState.isSelected() // Получаем состояние CheckBox
        );

        // Добавить запись в таблицу
        tableData.add(entry);

        // Очистить поля ввода
        ipTextField.clear();
        snmpTextField.clear();
        uplinkTextField.clear();
    }

    private void selectRow(ConfigEntry entry) {
        // Выполнить генерацию конфигурации и пинг
        clearSelection();
        //startPing(entry.getIp());
        //generateConfigFromTable(entry.getIp(), entry.getSnmp(), entry.getUplink(), entry.getSwitchType());
    }

    // Класс для хранения записей таблицы
    public static class ConfigEntry {
        private final String id;
        private final String host;
        private final String ip;
        private final String switchType;
        private final String snmp;
        private final String uplink;
        private final BooleanProperty isNewSwitch; // Новое поле для состояния коммутатора

        public ConfigEntry(String id, String host, String ip, String switchType, String snmp, String uplink, boolean isNewSwitch) {
            this.id = id;
            this.host = host;
            this.ip = ip;
            this.switchType = switchType;
            this.snmp = snmp;
            this.uplink = uplink;
            this.isNewSwitch = new SimpleBooleanProperty(isNewSwitch);
        }

        public String getId() {
            return id;
        }

        public String getHost() {
            return host;
        }

        public String getIp() {
            return ip;
        }

        public String getSwitchType() {
            return switchType;
        }

        public String getSnmp() {
            return snmp;
        }

        public String getUplink() {
            return uplink;
        }

        public boolean isNewSwitch() {
            return isNewSwitch.get();
        }

        public void setNewSwitch(boolean isNew) {
            this.isNewSwitch.set(isNew);
        }

        public BooleanProperty isNewSwitchProperty() {
            return isNewSwitch;
        }

    }

    @FXML
    private void onExecuteTasks() {
        List<ConfigEntry> entries = configTable.getItems(); // Ваш TableView
        if (entries.isEmpty()) {
            appendConfigTextArea("Список конфигураций пуст.");
            return;
        }

        // Разделяем задачи: с `isNewSwitch == true` и остальные
        List<ConfigEntry> prioritizedTasks = entries.stream()
                .filter(ConfigEntry::isNewSwitch) // Только записи с `CheckBox` == true
                .collect(Collectors.toList()); // Используем collect

        List<ConfigEntry> otherTasks = entries.stream()
                .filter(entry -> !entry.isNewSwitch()) // Все остальные записи
                .collect(Collectors.toList()); // Используем collect

        // Объединяем списки: сначала приоритетные, потом остальные
        List<ConfigEntry> allTasks = new ArrayList<>();
        allTasks.addAll(prioritizedTasks);
        allTasks.addAll(otherTasks);

        // Очищаем текстовые области
        configTextArea.clear();
        textAreaPingStatic.clear();
        textAreaPingDynamic.clear();

        // Передаем задачи в TelnetTaskExecutor
        telnetTaskExecutor.executeTasks(allTasks);
    }


    public void appendConfigTextArea(String message) {
        Platform.runLater(() -> configTextArea.appendText(message + "\n"));
    }

    public String getTemplate(String switchT) {
        // Замените на загрузку реальных шаблонов
        if (!switchT.isEmpty()) {
            DeviceTemplate deviceTemplate = templates.get(switchT);
            String config = deviceTemplate.getTemplate();
            return config;
        }else {
            return null;
        }
    }


}