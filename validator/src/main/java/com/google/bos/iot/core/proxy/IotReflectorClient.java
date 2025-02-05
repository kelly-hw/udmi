package com.google.bos.iot.core.proxy;

import static com.google.bos.iot.core.proxy.ProxyTarget.STATE_TOPIC;
import static com.google.common.base.Preconditions.checkState;
import static com.google.daq.mqtt.validator.Validator.REQUIRED_FUNCTION_VER;
import static com.google.udmi.util.CleanDateFormat.dateEquals;
import static com.google.udmi.util.Common.PUBLISH_TIME_KEY;
import static com.google.udmi.util.Common.TIMESTAMP_KEY;
import static com.google.udmi.util.Common.VERSION_KEY;
import static com.google.udmi.util.Common.getNamespacePrefix;
import static com.google.udmi.util.GeneralUtils.ifNotNullGet;
import static com.google.udmi.util.JsonUtil.asMap;
import static com.google.udmi.util.JsonUtil.convertTo;
import static com.google.udmi.util.JsonUtil.getDate;
import static com.google.udmi.util.JsonUtil.getTimestamp;
import static com.google.udmi.util.JsonUtil.stringify;
import static com.google.udmi.util.JsonUtil.toMap;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.api.client.util.Base64;
import com.google.common.base.Preconditions;
import com.google.daq.mqtt.util.MessagePublisher;
import com.google.daq.mqtt.validator.Validator;
import com.google.daq.mqtt.validator.Validator.ErrorContainer;
import com.google.udmi.util.Common;
import com.google.udmi.util.GeneralUtils;
import com.google.udmi.util.JsonUtil;
import com.google.udmi.util.SiteModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import udmi.schema.Credential;
import udmi.schema.Envelope;
import udmi.schema.Envelope.SubFolder;
import udmi.schema.Envelope.SubType;
import udmi.schema.ExecutionConfiguration;
import udmi.schema.IotAccess.IotProvider;
import udmi.schema.SetupUdmiConfig;
import udmi.schema.SetupUdmiState;
import udmi.schema.UdmiConfig;
import udmi.schema.UdmiState;

/**
 * Publish messages using the iot core reflector.
 */
public class IotReflectorClient implements MessagePublisher {

  public static final String UDMI_FOLDER = "udmi";
  public static final String UDMI_REFLECT = "UDMI-REFLECT";
  static final String REFLECTOR_KEY_ALGORITHM = "RS256";
  private static final String MOCK_DEVICE_NUM_ID = "123456789101112";
  private static final String UDMI_TOPIC = "events/" + UDMI_FOLDER;
  private static final long CONFIG_TIMEOUT_SEC = 10;
  private static final int UPDATE_RETRIES = 6;
  private static String prevTransactionId;
  private final String udmiVersion;
  private final CountDownLatch initialConfigReceived = new CountDownLatch(1);
  private final CountDownLatch initializedStateSent = new CountDownLatch(1);
  private final CountDownLatch validConfigReceived = new CountDownLatch(1);
  private final int requiredVersion;
  private final BlockingQueue<Validator.MessageBundle> messages = new LinkedBlockingQueue<>();
  private final MessagePublisher publisher;
  private final String subscriptionId;
  private final String registryId;
  private final String projectId;
  private final String updateTo;
  private Date reflectorStateTimestamp;
  private boolean isInstallValid;
  private boolean active;
  private Exception syncFailure;
  private SetupUdmiConfig udmiInfo;
  private int retries;

  /**
   * Create a new reflector instance.
   *
   * @param iotConfig       configuration file
   * @param requiredVersion version of the functions that are required by the tools
   */
  public IotReflectorClient(ExecutionConfiguration iotConfig, int requiredVersion) {
    Preconditions.checkState(requiredVersion >= REQUIRED_FUNCTION_VER,
        format("Min required version %s not satisfied by tools version %s", REQUIRED_FUNCTION_VER,
            requiredVersion));
    this.requiredVersion = requiredVersion;
    registryId = SiteModel.getRegistryActual(iotConfig);
    projectId = iotConfig.project_id;
    udmiVersion = Optional.ofNullable(iotConfig.udmi_version).orElseGet(Common::getUdmiVersion);
    updateTo = iotConfig.update_to;
    String cloudRegion = Optional.ofNullable(iotConfig.reflect_region)
        .orElse(iotConfig.cloud_region);
    String prefix = getNamespacePrefix(iotConfig.udmi_namespace);
    String iotProvider = ifNotNullGet(iotConfig.iot_provider, IotProvider::value,
        IotProvider.IMPLICIT.value());
    subscriptionId = format("%s/%s/%s/%s%s/%s",
        projectId, iotProvider, cloudRegion, prefix, UDMI_REFLECT, registryId);

    try {
      publisher = MessagePublisher.from(iotConfig, this::messageHandler, this::errorHandler);
    } catch (Exception e1) {
      throw new RuntimeException("While creating client " + subscriptionId, e1);
    }
    System.err.println("Subscribed to " + subscriptionId);

    try {
      System.err.println("Starting initial UDMI setup process");
      if (!initialConfigReceived.await(CONFIG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        System.err.println("Ignoring initial config received timeout (config likely empty)");
      }
      retries = updateTo == null ? 1 : UPDATE_RETRIES;
      while (validConfigReceived.getCount() > 0) {
        initializeReflectorState();
        initializedStateSent.countDown();
        if (!validConfigReceived.await(CONFIG_TIMEOUT_SEC, TimeUnit.SECONDS)) {
          retries--;
          if (retries <= 0) {
            throw new RuntimeException(
                "Config sync timeout expired. Investigate UDMI cloud functions install.",
                syncFailure);
          }
        }
      }

      active = true;
    } catch (Exception e) {
      publisher.close();
      throw new RuntimeException("Waiting for initial config", e);
    }
  }

  /**
   * Make an execution configuration that's used for reflector operations.
   *
   * @param iotConfig  basic non-reflector configuration
   * @param registryId the registry that will be reflected
   */
  public static ExecutionConfiguration makeReflectConfiguration(ExecutionConfiguration iotConfig,
      String registryId) {
    ExecutionConfiguration reflectConfiguration = new ExecutionConfiguration();
    reflectConfiguration.iot_provider = iotConfig.iot_provider;
    reflectConfiguration.project_id = iotConfig.project_id;
    reflectConfiguration.bridge_host = iotConfig.bridge_host;
    reflectConfiguration.reflector_endpoint = iotConfig.reflector_endpoint;
    reflectConfiguration.cloud_region = Optional.ofNullable(iotConfig.reflect_region)
        .orElse(iotConfig.cloud_region);

    reflectConfiguration.site_model = iotConfig.site_model;
    reflectConfiguration.registry_id = UDMI_REFLECT;
    reflectConfiguration.udmi_namespace = iotConfig.udmi_namespace;
    // Intentionally map registry -> device because of reflection registry semantics.
    reflectConfiguration.device_id = registryId;

    return reflectConfiguration;
  }

  /**
   * Get a new unique (not the same as previous one) transaction id.
   *
   * @return new unique transaction id
   */
  public static synchronized String getNextTransactionId() {
    String transactionId;
    do {
      transactionId = "RC:" + System.currentTimeMillis();
    } while (transactionId.equals(prevTransactionId));
    prevTransactionId = transactionId;
    return transactionId;
  }

  private void initializeReflectorState() {
    UdmiState udmiState = new UdmiState();
    udmiState.setup = new SetupUdmiState();
    udmiState.setup.user = System.getenv("USER");
    udmiState.setup.update_to = updateTo;
    try {
      reflectorStateTimestamp = new Date();
      System.err.printf("Setting state version %s timestamp %s%n",
          udmiVersion, getTimestamp(reflectorStateTimestamp));
      setReflectorState(udmiState);
    } catch (Exception e) {
      throw new RuntimeException("Could not set reflector state", e);
    }
  }

  private void setReflectorState(UdmiState udmiState) {
    Map<String, Object> map = new HashMap<>();
    map.put(TIMESTAMP_KEY, reflectorStateTimestamp);
    map.put(VERSION_KEY, udmiVersion);
    map.put(SubFolder.UDMI.value(), udmiState);

    System.err.println("UDMI setting reflectorState: " + stringify(map));

    publisher.publish(registryId, STATE_TOPIC, stringify(map));
  }

  @Override
  public Credential getCredential() {
    return publisher.getCredential();
  }

  private void messageHandler(String topic, String payload) {
    if (payload.length() == 0) {
      return;
    }
    byte[] rawData = payload.getBytes();
    boolean base64 = rawData[0] != '{';

    if ("null".equals(payload)) {
      return;
    }
    Map<String, Object> messageMap = asMap(
        new String(base64 ? Base64.decodeBase64(rawData) : rawData));
    try {
      List<String> parts = parseMessageTopic(topic);
      String category = parts.get(0);

      if (Common.CONFIG_CATEGORY.equals(category)) {
        ensureCloudSync(messageMap);
      } else if (Common.COMMANDS_CATEGORY.equals(category)) {
        handleCommandEnvelope(messageMap);
      } else {
        throw new RuntimeException("Unknown message category " + category);
      }
    } catch (Exception e) {
      if (isInstallValid) {
        handleReceivedMessage(extractAttributes(messageMap),
            new ErrorContainer(e, payload, JsonUtil.getTimestamp()));
      } else {
        throw e;
      }
    }
  }

  private void handleCommandEnvelope(Map<String, Object> messageMap) {
    if (!isInstallValid) {
      return;
    }
    Map<String, String> attributes = extractAttributes(messageMap);
    String payload = (String) messageMap.remove("payload");
    String decoded = GeneralUtils.decodeBase64(payload);
    Map<String, Object> message = asMap(decoded);
    handleReceivedMessage(attributes, message);
  }

  @NotNull
  private Map<String, String> extractAttributes(Map<String, Object> messageMap) {
    Map<String, String> attributes = new TreeMap<>();
    attributes.put("projectId", projectId);
    attributes.put("deviceRegistryId", registryId);
    attributes.put("deviceId", (String) messageMap.get("deviceId"));
    attributes.put("subType", (String) messageMap.get("subType"));
    attributes.put("subFolder", (String) messageMap.get("subFolder"));
    attributes.put("transactionId", (String) messageMap.get("transactionId"));
    attributes.put("deviceNumId", MOCK_DEVICE_NUM_ID);
    attributes.put(PUBLISH_TIME_KEY, (String) messageMap.get("publishTime"));
    return attributes;
  }

  private void handleReceivedMessage(Map<String, String> attributes,
      Map<String, Object> message) {
    Validator.MessageBundle messageBundle = new Validator.MessageBundle();
    messageBundle.attributes = attributes;
    messageBundle.message = message;
    messages.offer(messageBundle);
  }

  private boolean ensureCloudSync(Map<String, Object> message) {
    try {
      initialConfigReceived.countDown();
      if (initializedStateSent.getCount() > 0) {
        return false;
      }

      // Check for LEGACY UDMIS folder, and use that instead for backwards compatability. Once
      // UDMI version 1.4.2+ is firmly established, this can be simplified to just UDMI.
      boolean legacyConfig = message.containsKey("udmis");
      final UdmiConfig reflectorConfig;
      if (legacyConfig) {
        System.err.println("UDMI using LEGACY config format, function install upgrade required");
        reflectorConfig = new UdmiConfig();
        Map<String, Object> udmisMessage = toMap(message.get("udmis"));
        SetupUdmiConfig udmis = Optional.ofNullable(
                convertTo(SetupUdmiConfig.class, udmisMessage))
            .orElseGet(SetupUdmiConfig::new);
        reflectorConfig.last_state = getDate((String) udmisMessage.get("last_state"));
        reflectorConfig.setup = udmis;
      } else {
        reflectorConfig = Optional.ofNullable(
                convertTo(UdmiConfig.class, message.get(SubFolder.UDMI.value())))
            .orElseGet(UdmiConfig::new);
      }
      System.err.println("UDMI received reflectorConfig: " + stringify(reflectorConfig));
      Date lastState = reflectorConfig.last_state;
      System.err.println("UDMI matching against expected state timestamp " + getTimestamp(
          reflectorStateTimestamp));

      udmiInfo = reflectorConfig.setup;
      boolean timestampMatch = dateEquals(lastState, reflectorStateTimestamp);
      boolean versionMatch = ifNotNullGet(updateTo, to -> to.equals(udmiInfo.udmi_ref), true);

      if (timestampMatch && versionMatch) {
        if (!udmiVersion.equals(udmiInfo.udmi_version)) {
          System.err.println("UDMI version mismatch: " + udmiVersion);
        }

        System.err.printf("UDMI functions support versions %s:%s (required %s)%n",
            udmiInfo.functions_min, udmiInfo.functions_max, requiredVersion);
        String baseError = format("UDMI required functions version %d not allowed",
            requiredVersion);
        if (requiredVersion < udmiInfo.functions_min) {
          throw new RuntimeException(
              format("%s: min supported %s. Please update local UDMI install.", baseError,
                  udmiInfo.functions_min));
        }
        if (requiredVersion > udmiInfo.functions_max) {
          throw new RuntimeException(
              format("%s: max supported %s. Please update cloud UDMI install.",
                  baseError, udmiInfo.functions_max));
        }
        isInstallValid = true;
        validConfigReceived.countDown();
      } else if (!versionMatch) {
        System.err.println("UDMI update version mismatch... waiting for retry...");
      } else {
        System.err.println("UDMI ignoring mismatching timestamp " + getTimestamp(lastState));
      }
    } catch (Exception e) {
      syncFailure = e;
    }

    // Even through setup might be valid, return false to not process this config message.
    return false;
  }

  private List<String> parseMessageTopic(String topic) {
    List<String> parts = new ArrayList<>(Arrays.asList(topic.substring(1).split("/")));
    checkState("devices".equals(parts.remove(0)), "unknown parsed path field: " + topic);
    // Next field is registry, not device, since the reflector device holds the site registry.
    String parsedId = parts.remove(0);
    checkState(registryId.equals(parsedId),
        format("registry id %s does not match expected %s", parsedId, registryId));
    return parts;
  }

  private void errorHandler(Throwable throwable) {
    System.err.printf("Received mqtt client error: %s at %s%n",
        throwable.getMessage(), getTimestamp());
    close();
  }

  @Override
  public String getSubscriptionId() {
    return subscriptionId;
  }

  @Override
  public boolean isActive() {
    return active;
  }

  @Override
  public Validator.MessageBundle takeNextMessage(QuerySpeed timeout) {
    try {
      if (!active) {
        throw new IllegalStateException("Reflector client not active");
      }
      return messages.poll(timeout.seconds(), TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException("While taking next message", e);
    }
  }

  @Override
  public String publish(String deviceId, String topic, String data) {
    Envelope envelope = new Envelope();
    envelope.deviceRegistryId = registryId;
    envelope.deviceId = deviceId;
    String[] parts = topic.split("/", 2);
    envelope.subFolder = SubFolder.fromValue(parts[0]);
    envelope.subType = SubType.fromValue(parts[1]);
    envelope.payload = GeneralUtils.encodeBase64(data);
    String transactionId = getNextTransactionId();
    envelope.transactionId = transactionId;
    envelope.publishTime = new Date();
    publisher.publish(registryId, UDMI_TOPIC, stringify(envelope));
    return transactionId;
  }

  @Override
  public void close() {
    active = false;
    if (publisher != null) {
      publisher.close();
    }
  }

  @Override
  public SetupUdmiConfig getVersionInformation() {
    return requireNonNull(udmiInfo, "udmi version information not available");
  }

  public String getBridgeHost() {
    return publisher.getBridgeHost();
  }

  static class MessageBundle {

    Map<String, Object> message;
    Map<String, String> attributes;
  }

}
