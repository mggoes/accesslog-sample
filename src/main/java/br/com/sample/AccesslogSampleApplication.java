package br.com.sample;

import static java.lang.Boolean.TRUE;
import static java.lang.Runtime.getRuntime;
import static java.time.LocalTime.MIDNIGHT;
import static java.time.LocalTime.now;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.concurrent.TimeUnit.valueOf;
import static org.springframework.util.Assert.isTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.Undertow.Accesslog;
import org.springframework.boot.context.embedded.ConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import br.com.sample.AccesslogSampleApplication.PurgeProperties;
import io.undertow.servlet.api.DeploymentInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * The type Accesslog sample application.
 */
@SpringBootApplication
@EnableConfigurationProperties(PurgeProperties.class)
public class AccesslogSampleApplication {

	/**
	 * Main.
	 *
	 * @param args the args
	 */
	public static void main(final String... args) {
		SpringApplication.run(AccesslogSampleApplication.class, args);
	}

	/**
	 * The type Purge properties.
	 */
	@Data
	@ConfigurationProperties("accesslog.purge")
	public static class PurgeProperties implements InitializingBean {

		/**
		 * The constant ALLOWED_UNITS.
		 */
		private static final EnumSet<ChronoUnit> ALLOWED_UNITS = EnumSet.of(SECONDS, MINUTES, HOURS, DAYS);

		/**
		 * The Enabled.
		 */
		private boolean enabled;
		/**
		 * The Execute on startup.
		 */
		private boolean executeOnStartup;
		/**
		 * The Execution interval.
		 */
		private long executionInterval = 24;
		/**
		 * The Max history.
		 */
		private long maxHistory = 30;
		/**
		 * The Execution interval unit.
		 */
		private ChronoUnit executionIntervalUnit = HOURS;
		/**
		 * The Max history unit.
		 */
		private ChronoUnit maxHistoryUnit = DAYS;

		/**
		 * After properties set.
		 *
		 * @throws Exception the exception
		 */
		@Override
		public void afterPropertiesSet() throws Exception {
			isTrue(this.executionInterval > 0, "'executionInterval' must be greater than 0");
			isTrue(this.maxHistory > 0, "'maxHistory' must be greater than 0");
			isTrue(ALLOWED_UNITS.contains(this.executionIntervalUnit), "'executionIntervalUnit' must be one of the following units: SECONDS, MINUTES, HOURS, DAYS");
			isTrue(ALLOWED_UNITS.contains(this.maxHistoryUnit), "'maxHistoryUnit' must be one of the following units: SECONDS, MINUTES, HOURS, DAYS");
		}
	}

	/**
	 * The type Purgable accesslog config.
	 */
	@Configuration
	@AllArgsConstructor
	public class PurgableAccesslogConfig implements EmbeddedServletContainerCustomizer {

		/**
		 * The Server properties.
		 */
		private final ServerProperties serverProperties;
		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;

		/**
		 * Customize.
		 *
		 * @param container the container
		 */
		@Override
		public void customize(final ConfigurableEmbeddedServletContainer container) {
			final UndertowEmbeddedServletContainerFactory factory = (UndertowEmbeddedServletContainerFactory) container;
			final Accesslog accesslog = this.serverProperties.getUndertow().getAccesslog();
			if (accesslog != null && TRUE.equals(accesslog.getEnabled()) && this.purgeProperties.isEnabled()) {
				factory.addDeploymentInfoCustomizers(new PurgeableAccessLogDeploymentInfoCustomizer(this.purgeProperties, accesslog));
			}
		}
	}

	/**
	 * The type Purgeable access log deployment info customizer.
	 */
	@AllArgsConstructor
	public static class PurgeableAccessLogDeploymentInfoCustomizer implements UndertowDeploymentInfoCustomizer {

		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;
		/**
		 * The Accesslog.
		 */
		private final Accesslog accesslog;

		/**
		 * Customize.
		 *
		 * @param deploymentInfo the deployment info
		 */
		@Override
		public void customize(final DeploymentInfo deploymentInfo) {
			long initialDelay = 0;

			if (!this.purgeProperties.isExecuteOnStartup()) {
				initialDelay = MILLIS.between(now(), MIDNIGHT);
			}

			final PurgeTask purgeTask = new PurgeTask(this.purgeProperties, this.accesslog);
			final long executionInterval = this.purgeProperties.getExecutionInterval();
			final String executionIntervalUnit = this.purgeProperties.getExecutionIntervalUnit().name();

			Executors.newScheduledThreadPool(getRuntime().availableProcessors())
					.scheduleWithFixedDelay(purgeTask, initialDelay, executionInterval, valueOf(executionIntervalUnit));
		}

	}

	/**
	 * The type Purge task.
	 */
	@Slf4j
	public static class PurgeTask implements Runnable {

		/**
		 * The constant ANY_CHARACTER_PATTERN.
		 */
		private static final String ANY_CHARACTER_PATTERN = ".*";
		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;
		/**
		 * The Path.
		 */
		private final Path path;
		/**
		 * The Current log file name.
		 */
		private final String currentLogFileName;
		/**
		 * The Pattern.
		 */
		private final String pattern;

		/**
		 * Instantiates a new Purge task.
		 *
		 * @param purgeProperties the purge properties
		 * @param accesslog       the accesslog
		 */
		public PurgeTask(final PurgeProperties purgeProperties, final Accesslog accesslog) {
			this.purgeProperties = purgeProperties;
			this.path = accesslog.getDir().toPath();
			this.currentLogFileName = accesslog.getPrefix() + accesslog.getSuffix();
			this.pattern = this.buildPattern(accesslog);
		}

		/**
		 * Run.
		 */
		@Override
		public void run() {
			log.trace("Purging access log files...");
			try {
				Files.list(this.path).filter(this::isPurgeable).forEach(this::purge);
			} catch (final IOException e) {
				log.error(e.getMessage(), e);
			}
			log.trace("Purging finished!");
		}

		/**
		 * Is purgeable boolean.
		 *
		 * @param accessLogPath the access log path
		 * @return the boolean
		 */
		private boolean isPurgeable(final Path accessLogPath) {
			boolean purgeable = false;
			try {
				final String fileName = accessLogPath.getFileName().toString();
				log.trace("File name: {}", fileName);

				if (!this.currentLogFileName.equals(fileName) && fileName.matches(this.pattern)) {
					final FileTime lastModifiedTime = Files.getLastModifiedTime(accessLogPath);
					final Instant lastModifiedInstant = Instant.ofEpochMilli(lastModifiedTime.toMillis());
					log.trace("Last modified instant: {}", lastModifiedInstant);

					final Instant now = Instant.now();
					log.trace("Now: {}", now);

					final ChronoUnit maxHistoryUnit = this.purgeProperties.getMaxHistoryUnit();
					final long between = maxHistoryUnit.between(lastModifiedInstant, now);
					log.trace("Between: {} {}", between, maxHistoryUnit);

					purgeable = between > this.purgeProperties.getMaxHistory();
				}

				log.trace("Purgeable: {}", purgeable);
			} catch (final IOException e) {
				log.error(e.getMessage(), e);
			}
			return purgeable;
		}

		/**
		 * Purge.
		 *
		 * @param accessLogPath the access log path
		 */
		private void purge(final Path accessLogPath) {
			try {
				final boolean deleted = Files.deleteIfExists(accessLogPath);
				log.trace("Deleted: {}", deleted);
			} catch (final IOException e) {
				log.error(e.getMessage(), e);
			}
		}

		/**
		 * Build pattern string.
		 *
		 * @param accesslog the accesslog
		 * @return the string
		 */
		private String buildPattern(final Accesslog accesslog) {
			return new StringBuilder()
					.append(this.normalizeDotCharacter(accesslog.getPrefix()))
					.append(ANY_CHARACTER_PATTERN)
					.append(this.normalizeDotCharacter(accesslog.getSuffix()))
					.append(ANY_CHARACTER_PATTERN)
					.toString();
		}

		/**
		 * Normalize dot character string.
		 *
		 * @param text the text
		 * @return the string
		 */
		private String normalizeDotCharacter(final String text) {
			return text.replace(".", "\\.");
		}
	}
}
