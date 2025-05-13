module com.netonline.switchconfig {
    requires javafx.controls;
    requires javafx.fxml;
    requires com.fasterxml.jackson.databind;
    //requires ini4j;
    //requires org.apache.commons.configuration2;

    opens com.netonline.switchconfig to javafx.fxml;
    exports com.netonline.switchconfig;
}