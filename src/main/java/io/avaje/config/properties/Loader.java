package io.avaje.config.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Loads the configuration from known/expected locations.
 * <p>
 * Defines the loading order of resources and files.
 * </p>
 */
class Loader {

  private static final Logger log = LoggerFactory.getLogger(Loader.class);

  private static final Pattern SPLIT_PATHS = Pattern.compile("[\\s,;]+");

  enum Source {
    RESOURCE,
    FILE
  }

  private final LoadContext loadContext = new LoadContext();

  private YamlLoader yamlLoader;

  Loader() {
    if (!"true".equals(System.getProperty("skipYaml"))) {
      try {
        Class<?> exists = Class.forName("org.yaml.snakeyaml.Yaml");
        if (exists != null) {
          yamlLoader = new YamlLoader(loadContext);
        }
      } catch (Exception e) {
        // ignored
      }
    }
  }

  /**
   * Load the configuration with the expected ordering.
   */
  void load() {

    loadMain(Source.RESOURCE);
    // external file configuration overrides the resources configuration
    loadMain(Source.FILE);

    loadViaSystemProperty();
    loadViaIndirection();

    // test configuration (if found) overrides main configuration
    // we should only find these resources when running tests
    if (!loadTest()) {
      loadLocalDev();
    }
  }

  /**
   * Provides a way to override properties when running via main() locally.
   */
  private void loadLocalDev() {

    File localDev = new File(System.getProperty("user.home"), ".localdev");
    if (localDev.exists()) {
      final String appName = loadContext.getAppName();
      if (appName != null) {
        final String prefix = localDev.getAbsolutePath() + File.separator + appName;
        if (log.isDebugEnabled()) {
          log.debug("looking to load .localdev yaml/properties for appName:{}", appName);
        }
        loadFileWithExtensionCheck(prefix + ".yaml");
        loadFileWithExtensionCheck(prefix + ".properties");
      }
    }
  }

  /**
   * Load test configuration.
   *
   * @return true if test properties have been loaded.
   */
  private boolean loadTest() {
    int before = loadContext.size();
    loadProperties("application-test.properties", Source.RESOURCE);
    loadYaml("application-test.yaml", Source.RESOURCE);
    loadYaml("application-test.yml", Source.RESOURCE);
    return loadContext.size() > before;
  }

  /**
   * Load configuration defined by a <em>load.properties</em> entry in properties file.
   */
  private void loadViaIndirection() {

    String location = loadContext.indirectLocation();
    if (location != null) {
      final String[] paths = splitPaths(location);
      for (String path : paths) {
        loadFileWithExtensionCheck(PropertyEval.eval(path));
      }
    }
  }

  String[] splitPaths(String location) {
    return SPLIT_PATHS.split(location);
  }

  /**
   * Load the main configuration for the given source.
   */
  private void loadMain(Source source) {
    loadYaml("application.yaml", source);
    loadYaml("application.yml", source);
    loadProperties("application.properties", source);
  }

  private void loadViaSystemProperty() {
    String fileName = System.getenv("PROPS_FILE");
    if (fileName == null) {
      fileName = System.getProperty("props.file");
      if (fileName != null) {
        loadFileWithExtensionCheck(fileName);
      }
    }
  }

  void loadFileWithExtensionCheck(String fileName) {
    if (fileName.endsWith("yaml") || fileName.endsWith("yml")) {
      loadYaml(fileName, Source.FILE);
    } else if (fileName.endsWith("properties")) {
      loadProperties(fileName, Source.FILE);
    } else {
      throw new IllegalArgumentException("Expecting only yaml or properties file but got [" + fileName + "]");
    }
  }

  /**
   * Evaluate all the configuration entries and return as properties.
   */
  Properties eval() {
    return loadContext.eval();
  }

  void loadYaml(String resourcePath, Source source) {
    if (yamlLoader != null) {
      try {
        try (InputStream is = resource(resourcePath, source)) {
          yamlLoader.load(is);
        }
      } catch (Exception e) {
        log.warn("Failed to read yml from:" + resourcePath, e);
      }
    }
  }

  void loadProperties(String resourcePath, Source source) {
    try {
      try (InputStream is = resource(resourcePath, source)) {
        if (is != null) {
          loadProperties(is);
        }
      }
    } catch (Exception e) {
      log.warn("Failed to read properties from:" + resourcePath, e);
    }
  }

  private InputStream resource(String resourcePath, Source source) {
    return loadContext.resource(resourcePath, source);
  }

  private void loadProperties(InputStream is) {
    if (is != null) {
      try {
        Properties properties = new Properties();
        properties.load(is);
        put(properties);
      } catch (IOException e) {
        throw new RuntimeException("Failed to load properties?", e);
      } finally {
        close(is);
      }
    }
  }

  private void put(Properties properties) {
    Enumeration<?> enumeration = properties.propertyNames();
    while (enumeration.hasMoreElements()) {
      String key = (String) enumeration.nextElement();
      String property = properties.getProperty(key);
      loadContext.put(key, property);
    }
  }

  private void close(InputStream is) {
    try {
      is.close();
    } catch (IOException e) {
      log.warn("Error closing input stream for properties", e);
    }
  }
}