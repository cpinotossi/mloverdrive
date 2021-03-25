// Licensed under the MIT License.

package com.cpt.az;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorContext;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusFailureReason;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.ServiceBusReceiverAsyncClient;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import reactor.core.Disposable;

public class ServiceBusReceiver {

    static final String SERVICE_BUS_QUEUE_NAME = System.getenv("SERVICE_BUS_QUEUE_NAME");
    static final String STORAGE_OUTPUT_CONNECTION_STRING = System.getenv("STORAGE_OUTPUT_CONNECTION_STRING");
    static final String STORAGE_INPUT_CONNECTION_STRING = System.getenv("STORAGE_INPUT_CONNECTION_STRING");
    static final String SERVICE_BUS_CONNECTION_STRING = System.getenv("SERVICE_BUS_CONNECTION_STRING");
    static final String STORAGE_OUTPUT_CONTAINER_NAME = System.getenv("STORAGE_OUTPUT_CONTAINER_NAME");
    static final String STORAGE_INPUT_CONTAINER_NAME = System.getenv("STORAGE_INPUT_CONTAINER_NAME");
    static final String APPLICATIONINSIGHTS_KEY = System.getenv("APPLICATIONINSIGHTS_KEY");
    static final String VM_NAME = System.getenv("VM_NAME");
    static final String IS_SERVICE_BUS = System.getenv("IS_SERVICE_BUS");
    static final String _JAVA_OPTIONS = System.getenv("_JAVA_OPTIONS");
    static final String LOG_LEVEL = System.getenv("LOG_LEVEL");
    final static Gson gson = new GsonBuilder().create();
    private static BlobContainerClient inputBlobContainerClient = null;
    private static BlobContainerClient outputBlobContainerClient = null;
    private static SimpleDateFormat sdf = new SimpleDateFormat("MMM dd yyyy HH:mm:ss.SSS zzz");

    public static void main(String[] args) throws Exception {
        // Setup Logging
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, LOG_LEVEL);
        showEnv();
        // Support local test without Azure Service Bus
        // Init Blob Storage Connection for Input file
        inputBlobContainerClient = (new BlobServiceClientBuilder().connectionString(STORAGE_INPUT_CONNECTION_STRING)
                .buildClient()).getBlobContainerClient(STORAGE_INPUT_CONTAINER_NAME);
        // Init Blob Storage Connection for Output file
        outputBlobContainerClient = (new BlobServiceClientBuilder().connectionString(STORAGE_OUTPUT_CONNECTION_STRING)
                .buildClient()).getBlobContainerClient(STORAGE_OUTPUT_CONTAINER_NAME);

        Boolean isServiceBus = false;
        if (IS_SERVICE_BUS != null) {
            isServiceBus = Boolean.valueOf(IS_SERVICE_BUS);
        }
        if (isServiceBus) {
            // Init Subscribe to Message Queue
            receiveMessages();
        } else {
            // Test without Azure Service Bus
            Metric m = new Metric();
            m.deviceName = VM_NAME;
            String problemRow = System.getenv("PROBLEM_ROW");
            String problemCol = System.getenv("PROBLEM_COL");
            if (problemCol == null) {
                problemCol = "5000";
                problemRow = "150";
            }
            // Exec Problem
            // Define Endtime
            Date startDateMessage = Calendar.getInstance().getTime();
            Calendar calStartTimeMessage = Calendar.getInstance();
            calStartTimeMessage.setTime(startDateMessage);
            long startTimeMessage = calStartTimeMessage.getTimeInMillis();
            m.startMessage = startTimeMessage;
            Problem problem = new Problem(Integer.parseInt(problemRow), Integer.parseInt(problemCol));

            // Define Endtime
            Date endDateMessage = Calendar.getInstance().getTime();
            Calendar calEndTimeMessage = Calendar.getInstance();
            calEndTimeMessage.setTime(endDateMessage);
            long endTimeMessage = calEndTimeMessage.getTimeInMillis();
            long durationMessage = endTimeMessage - startTimeMessage;
            m.durationMessage = durationMessage;
            m.startMessageDate = sdf.format(startDateMessage);
            m.endMessageDate = sdf.format(endDateMessage);

            System.out.println("startTime:\t" + startTimeMessage + "\t:" + sdf.format(startDateMessage));
            System.out.println("endTime:\t" + endTimeMessage + "\t:" + sdf.format(endDateMessage));

            // Collect JVM Metrics
            Runtime runtime = Runtime.getRuntime();
            // Not Working with my Docker Containers
            // final String javaRT = Runtime.version().toString();
            final long mb = 1024 * 1024;
            String[] jOptArry = _JAVA_OPTIONS.split(" ");
            m.jvmXmx = jOptArry[0].substring(1);
            m.jvmXms = jOptArry[1].substring(1);
            m.jvmProcessors = runtime.availableProcessors();
            m.jvmMaxMemory = runtime.maxMemory() / mb;
            m.jvmTotalMemory = runtime.totalMemory() / mb;
            m.jvmFreeMemory = runtime.freeMemory() / mb;
            m.jvmUsagedMemory = (m.jvmFreeMemory + (m.jvmMaxMemory - m.jvmTotalMemory));
            // Print Memory Usage
            setOutputBlob(m.startMessage + ".txt", problem.getTrashBack());
            System.out.println(gson.toJson(m));
            m.printDelta();
        }
    }

    // handles received messages
    static void receiveMessages() throws InterruptedException {
        CountDownLatch countdownLatch = new CountDownLatch(1);
        // TODO is peaklook default
        // Create an instance of the processor through the ServiceBusClientBuilder
        // based on
        // https://github.com/Azure/azure-sdk-for-java/tree/master/sdk/servicebus/azure-messaging-servicebus

        /*
        ServiceBusProcessorClient processorClient = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS_CONNECTION_STRING).processor().queueName(SERVICE_BUS_QUEUE_NAME)
                .processMessage(ServiceBusReceiver::processMessage)
                .processError(context -> processError(context, countdownLatch)).disableAutoComplete()
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE).prefetchCount(0).buildProcessorClient();
        // Start listining for incomming Azure Service Bus Messages;
        processorClient.start();
        */
        
        //https://docs.microsoft.com/en-us/java/api/com.azure.messaging.servicebus.servicebusreceiverasyncclient?view=azure-java-stable
        ServiceBusReceiverAsyncClient consumer = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS_CONNECTION_STRING).receiver().queueName(SERVICE_BUS_QUEUE_NAME).prefetchCount(0).disableAutoComplete()
                .receiveMode(ServiceBusReceiveMode.RECEIVE_AND_DELETE)
                .buildAsyncClient();

        Disposable subscription = consumer.receiveMessages().subscribe(message -> {
            processMessage(message);
        }, error -> System.out.println("Error occurred: " + error), () -> System.out.println("Receiving complete."));

        // When program ends, or you're done receiving all messages.
        // subscription.dispose();
        // consumer.close();
    }

    private static void processMessage(ServiceBusReceivedMessage message) {
        // private static void processMessage(ServiceBusReceivedMessageContext context)
        // {
        // ServiceBusReceivedMessage message = context.getMessage();
        try {
            Metric m = new Metric();
            m.deviceName = VM_NAME;
            // Parse incoming Service Bus Message
            POJOEventSchema eventGridEvent = gson.fromJson(message.getBody().toString(), POJOEventSchema.class);
            String urlString = eventGridEvent.data.get("url").toString();
            String fileName = urlString.substring(urlString.lastIndexOf('/') + 1, urlString.length());
            m.fileName = fileName;
            // Retrieve Metadata from corresponding Storage Blog
            // Exec Problem
            final long startTimeInputBlob = System.currentTimeMillis();
            BlobClient blobClientInput = inputBlobContainerClient.getBlobClient(fileName);
            blobClientInput.getProperties().getMetadata().toString();
            Map<String, String> blobMetadataMap = blobClientInput.getProperties().getMetadata();
            String colsBlob = blobMetadataMap.get("cols");
            String rowsBlob = blobMetadataMap.get("rows");
            long durationInputBlob = (System.currentTimeMillis() - startTimeInputBlob);
            m.durationInputBlob = durationInputBlob;

            // Extrat the Timestamp provided by the input Blob and use as start time.
            // Timeformat used by Jmeter when sending the input Blob

            String timestamp = blobMetadataMap.get("starttime");// example "Mar 23 2021 12:32:31.620 CET";
            java.util.Date startDateMessage = sdf.parse(timestamp);
            Calendar calStartTime = Calendar.getInstance();
            calStartTime.setTime(startDateMessage);
            // the start time as UTC milliseconds from the epoch.
            long startTimeMessage = calStartTime.getTimeInMillis();
            m.startMessage = startTimeMessage;

            // Exec Problem
            final long startTimeProcessProblem = System.currentTimeMillis();
            Problem problem = new Problem(Integer.parseInt(rowsBlob), Integer.parseInt(colsBlob));
            long durationProcessProblem = (System.currentTimeMillis() - startTimeProcessProblem);
            m.durationProblem = durationProcessProblem;

            // Collect JVM Metrics
            Runtime runtime = Runtime.getRuntime();
            // Not Working with my Docker Containers
            // final String javaRT = Runtime.version().toString();
            final long mb = 1024 * 1024;
            String[] jOptArry = _JAVA_OPTIONS.split(" ");
            m.jvmXmx = jOptArry[0].substring(1);
            m.jvmXms = jOptArry[1].substring(1);
            m.jvmProcessors = runtime.availableProcessors();
            m.jvmMaxMemory = runtime.maxMemory() / mb;
            m.jvmTotalMemory = runtime.totalMemory() / mb;
            m.jvmFreeMemory = runtime.freeMemory() / mb;
            m.jvmUsagedMemory = (m.jvmFreeMemory + (m.jvmMaxMemory - m.jvmTotalMemory));

            // Write Blob
            final long startTimeOutputBlob = System.currentTimeMillis();
            setOutputBlob(fileName, problem.getTrashBack());
            long durationOutputBlob = (System.currentTimeMillis() - startTimeOutputBlob);
            m.durationOutputBlob = durationOutputBlob;

            // Define Endtime
            Date endDateMessage = Calendar.getInstance().getTime();
            Calendar calEndTimeMessage = Calendar.getInstance();
            calEndTimeMessage.setTime(endDateMessage);
            long endTimeMessage = calEndTimeMessage.getTimeInMillis();
            m.endMessage = endTimeMessage;
            long durationMessage = endTimeMessage - startTimeMessage;
            m.durationMessage = durationMessage;
            m.startMessageDate = sdf.format(startDateMessage);
            m.endMessageDate = sdf.format(endDateMessage);
            System.out.println(gson.toJson(m));
            m.printDelta();
        } catch (JsonSyntaxException e) {
            System.err.println("Unable to parse Event Message to Object");
        } catch (Exception e) {
            System.err.println("Unexpected Exception");
        }
    }

    private static void setOutputBlob(String fileName, String content) throws Exception {
        BlobClient blobClient = outputBlobContainerClient.getBlobClient(fileName);
        BlockBlobClient blockBlobClient = blobClient.getBlockBlobClient();
        ByteArrayInputStream dataStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        blockBlobClient.upload(dataStream, content.length());
    }

    private static void showEnv() {
        System.out.printf("IsSB:%s\nSBQN:%s\nSOCS:%s\nSICS:%s\nSBCS:%s\nSOCN:%s\nSICN:%s\n", IS_SERVICE_BUS,
                SERVICE_BUS_QUEUE_NAME, STORAGE_OUTPUT_CONNECTION_STRING.substring(0, 10),
                STORAGE_INPUT_CONNECTION_STRING.substring(0, 10), SERVICE_BUS_CONNECTION_STRING.substring(0, 10),
                STORAGE_OUTPUT_CONTAINER_NAME, STORAGE_INPUT_CONTAINER_NAME);
    }

    private static void processError(ServiceBusErrorContext context, CountDownLatch countdownLatch) {
        System.out.printf("Error when receiving messages from namespace: '%s'. Entity: '%s'%n",
                context.getFullyQualifiedNamespace(), context.getEntityPath());
        if (!(context.getException() instanceof ServiceBusException)) {
            System.out.printf("Non-ServiceBusException occurred: %s%n", context.getException());
            return;
        }
        ServiceBusException exception = (ServiceBusException) context.getException();
        ServiceBusFailureReason reason = exception.getReason();
        if (reason == ServiceBusFailureReason.MESSAGING_ENTITY_DISABLED
                || reason == ServiceBusFailureReason.MESSAGING_ENTITY_NOT_FOUND
                || reason == ServiceBusFailureReason.UNAUTHORIZED) {
            System.out.printf("An unrecoverable error occurred. Stopping processing with reason %s: %s%n", reason,
                    exception.getMessage());

            countdownLatch.countDown();
        } else if (reason == ServiceBusFailureReason.MESSAGE_LOCK_LOST) {
            System.out.printf("Message lock lost for message: %s%n", context.getException());
        } else if (reason == ServiceBusFailureReason.SERVICE_BUSY) {
            try {
                // Choosing an arbitrary amount of time to wait until trying again.
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                System.err.println("Unable to sleep for period of time");
            }
        } else {
            System.out.printf("Error source %s, reason %s, message: %s%n", context.getErrorSource(), reason,
                    context.getException());
        }
    }
}

class Metric {
    public long startMessage = 0l;
    public long endMessage = 0l;
    public long durationMessage = 0l;
    public long durationInputBlob = 0l;
    public long durationOutputBlob = 0l;
    public long durationProblem = 0l;
    public String startMessageDate = "";
    public String endMessageDate = "";
    public String deviceName = "";
    public long jvmProcessors = 0;
    public long jvmMaxMemory = 0;
    public long jvmTotalMemory = 0;
    public long jvmFreeMemory = 0;
    public long jvmUsagedMemory = 0;
    public String jvmXmx = "";
    public String jvmXms = "";
    public String fileName = "";

    public void printRow() {
        String header = "Device|DuraM[ms]|DuraIB[ms]|DuraP[ms]|DuraOB[ms]|CPU|J-Xms|J-Xmx|J-M[MB]|J-F[MB]|J-T[MB]|FileName";
        String row = durationMessage + "|" + durationInputBlob + "|" + durationProblem + "|" + durationOutputBlob + "|"
                + jvmProcessors + "|" + jvmXms + "|" + jvmXmx + "|" + jvmMaxMemory + "|" + jvmFreeMemory + "|"
                + jvmTotalMemory + "|" + fileName;
        System.out.println(header + "\n" + row);
    }

    public void printDelta() {
        System.out.println("startTime:\t" + startMessage + "\t:" + startMessageDate);
        System.out.println("endTime:\t" + endMessage + "\t:" + endMessageDate);
        System.out.println("delta:\t" + (endMessage - startMessage));
    }
}

class POJOEventSchema {
    public String topic;
    public String subject;
    public String eventType;
    public Date eventTime;
    public String id;
    public String dataVersion;
    public String metadataVersion;
    public Map<String, Object> data;
}