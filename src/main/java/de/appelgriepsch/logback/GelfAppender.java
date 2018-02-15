package de.appelgriepsch.logback;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.Layout;

import org.graylog2.gelfclient.GelfConfiguration;
import org.graylog2.gelfclient.GelfMessageBuilder;
import org.graylog2.gelfclient.GelfMessageLevel;
import org.graylog2.gelfclient.GelfTransports;
import org.graylog2.gelfclient.transport.GelfTransport;

import org.slf4j.Marker;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import java.util.HashMap;
import java.util.Map;

import static de.appelgriepsch.logback.MessageLevelMapping.toGelfNumericValue;


/**
 * @author  Sandra Thieme - thieme@synyx.de
 */
public class GelfAppender extends AppenderBase<ILoggingEvent> {

    private String server = "localhost";
    private int port = 12201;
    private String hostName;
    private String protocol = "UDP";
    private boolean includeSource = true;
    private boolean includeMDC = true;
    private boolean includeStackTrace = true;
    private boolean includeLevelName = false;
    private int queueSize = 512;
    private int connectTimeout = 1000;
    private int reconnectDelay = 500;
    private int sendBufferSize = -1;
    private boolean tcpNoDelay = false;
    private boolean tcpKeepAlive = false;
    private Map<String, Object> additionalFields = new HashMap<>();
    private ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();
    private Layout<ILoggingEvent> layout;

    private GelfTransport client;

    @Override
    protected void append(ILoggingEvent event) {

        if (event == null) {
            return;
        }

        // create a copy of the logging event to avoid passing exception stacktraces to GELF's short_message field
        LoggingEvent copy = new LoggingEvent();
        copy.setMessage(event.getMessage());
        copy.setLevel(event.getLevel());
        copy.setArgumentArray(event.getArgumentArray());
        copy.setLoggerName(event.getLoggerName());
        copy.setThreadName(event.getThreadName());
        copy.setTimeStamp(event.getTimeStamp());
        copy.setMDCPropertyMap(copy.getMDCPropertyMap());

        final GelfMessageBuilder builder = new GelfMessageBuilder(this.layout.doLayout(copy), hostName()).timestamp(
                    event.getTimeStamp() / 1000d)
                .level(GelfMessageLevel.fromNumericLevel(toGelfNumericValue(event.getLevel())))
                .additionalField("loggerName", event.getLoggerName())
                .additionalField("threadName", event.getThreadName());

        final Marker marker = event.getMarker();

        if (marker != null) {
            builder.additionalField("marker", marker.getName());
        }

        if (includeMDC) {
            for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
                builder.additionalField(entry.getKey(), entry.getValue());
            }
        }

        StackTraceElement[] callerData = event.getCallerData();

        if (includeSource && event.hasCallerData() && callerData.length > 0) {
            StackTraceElement source = callerData[0];

            builder.additionalField("sourceFileName", source.getFileName());
            builder.additionalField("sourceMethodName", source.getMethodName());
            builder.additionalField("sourceClassName", source.getClassName());
            builder.additionalField("sourceLineNumber", source.getLineNumber());
        }

        IThrowableProxy thrown = event.getThrowableProxy();

        if (includeStackTrace && thrown != null) {
            String convertedThrowable = throwableConverter.convert(event);

            builder.additionalField("exceptionClass", thrown.getClassName());
            builder.additionalField("exceptionMessage", thrown.getMessage());
            builder.additionalField("exceptionStackTrace", convertedThrowable);

            builder.fullMessage(event.getFormattedMessage() + "\n\n" + convertedThrowable);
        } else {
            builder.fullMessage(event.getFormattedMessage());
        }

        if (includeLevelName) {
            builder.additionalField("levelName", event.getLevel().levelStr);
        }

        if (!additionalFields.isEmpty()) {
            builder.additionalFields(additionalFields);
        }

        if(!client.trySend(builder.build())) {
            addError("Failed to write log event to the GELF server using trySend");
        }
    }


    @Override
    public void start() {
        if (this.layout == null) {
            PatternLayout patternLayout = new PatternLayout();
            patternLayout.setContext(context);
            patternLayout.setPattern("%m %n");
            patternLayout.start();
            this.layout = patternLayout;
        }
        createGelfClient();
        throwableConverter.start();
        super.start();
    }


    @Override
    public void stop() {

        super.stop();
        client.stop();
        throwableConverter.stop();
    }


    private String hostName() {

        if (hostName == null || hostName.trim().isEmpty()) {
            try {
                return InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                return "localhost";
            }
        }

        return hostName;
    }


    private void createGelfClient() {

        client = GelfTransports.create(getGelfConfiguration());
    }


    private GelfConfiguration getGelfConfiguration() {

        final InetSocketAddress serverAddress = new InetSocketAddress(server, port);
        final GelfTransports gelfProtocol = GelfTransports.valueOf(protocol().toUpperCase());

        return new GelfConfiguration(serverAddress).transport(gelfProtocol)
            .queueSize(queueSize)
            .connectTimeout(connectTimeout)
            .reconnectDelay(reconnectDelay)
            .sendBufferSize(sendBufferSize)
            .tcpNoDelay(tcpNoDelay)
            .tcpKeepAlive(tcpKeepAlive);
    }


    private String protocol() {

        if (!"UDP".equalsIgnoreCase(protocol) && !"TCP".equalsIgnoreCase(protocol)) {
            return "UDP";
        }

        return protocol;
    }


    public void setServer(String server) {

        this.server = server;
    }


    public String getServer() {

        return server;
    }


    public void setPort(int port) {

        this.port = port;
    }


    public int getPort() {

        return port;
    }


    public void setHostName(String hostName) {

        this.hostName = hostName;
    }


    public String getHostName() {

        return hostName;
    }


    public void setProtocol(String protocol) {

        this.protocol = protocol;
    }


    public String getProtocol() {

        return protocol;
    }


    public void setIncludeSource(boolean includeSource) {

        this.includeSource = includeSource;
    }


    public boolean isIncludeSource() {

        return includeSource;
    }


    public void setIncludeMDC(boolean includeMDC) {

        this.includeMDC = includeMDC;
    }


    public boolean isIncludeMDC() {

        return includeMDC;
    }


    public void setIncludeStackTrace(boolean includeStackTrace) {

        this.includeStackTrace = includeStackTrace;
    }


    public boolean isIncludeStackTrace() {

        return includeStackTrace;
    }


    public void setIncludeLevelName(boolean includeLevelName) {

        this.includeLevelName = includeLevelName;
    }


    public boolean isIncludeLevelName() {

        return includeLevelName;
    }


    public void setQueueSize(int queueSize) {

        this.queueSize = queueSize;
    }


    public int getQueueSize() {

        return queueSize;
    }


    public void setConnectTimeout(int connectTimeout) {

        this.connectTimeout = connectTimeout;
    }


    public int getConnectTimeout() {

        return connectTimeout;
    }


    public void setReconnectDelay(int reconnectDelay) {

        this.reconnectDelay = reconnectDelay;
    }


    public int getReconnectDelay() {

        return reconnectDelay;
    }


    public void setSendBufferSize(int sendBufferSize) {

        this.sendBufferSize = sendBufferSize;
    }


    public int getSendBufferSize() {

        return sendBufferSize;
    }


    public void setTcpNoDelay(boolean tcpNoDelay) {

        this.tcpNoDelay = tcpNoDelay;
    }


    public boolean isTcpNoDelay() {

        return tcpNoDelay;
    }


    public void setTcpKeepAlive(boolean tcpKeepAlive) {

        this.tcpKeepAlive = tcpKeepAlive;
    }


    public boolean isTcpKeepAlive() {

        return tcpKeepAlive;
    }


    public void setAdditionalFields(String additionalFields) {

        try {
            String[] values = additionalFields.split(",");

            for (String field : values) {
                String[] components = field.split("=");
                this.additionalFields.put(components[0], components[1]);
            }
        } catch (Exception e) {
            addWarn("Failed to read additional fields: " + e.getMessage(), e);
        }
    }


    public void addAdditionalField(String key, String value) {

        try {
            this.additionalFields.put(key, value);
        } catch (Exception e) {
            addWarn("Failed to add additional field: " + e.getMessage(), e);
        }
    }


    public Map<String, Object> getAdditionalFields() {

        return additionalFields;
    }


    public void setLayout(Layout<ILoggingEvent> layout) {

        this.layout = layout;
    }


    public Layout<ILoggingEvent> getLayout() {

        return layout;
    }
}
