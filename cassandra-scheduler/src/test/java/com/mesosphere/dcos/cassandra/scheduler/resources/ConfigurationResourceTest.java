package com.mesosphere.dcos.cassandra.scheduler.resources;

import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.io.Resources;
import com.mesosphere.dcos.cassandra.common.config.CassandraConfig;
import com.mesosphere.dcos.cassandra.common.serialization.IntegerStringSerializer;
import com.mesosphere.dcos.cassandra.scheduler.config.*;
import com.mesosphere.dcos.cassandra.scheduler.persistence.ZooKeeperPersistence;
import io.dropwizard.configuration.ConfigurationFactory;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.testing.junit.ResourceTestRule;
import io.dropwizard.validation.BaseValidator;
import org.apache.curator.test.TestingServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class ConfigurationResourceTest {
    private static TestingServer server;

    private static ZooKeeperPersistence persistence;

    private static CassandraSchedulerConfiguration config;

    private static ConfigurationManager manager;

    @Rule
    public final ResourceTestRule resources = ResourceTestRule.builder()
            .addResource(new ConfigurationResource(manager)).build();

    @BeforeClass
    public static void beforeAll() throws Exception {

        server = new TestingServer();

        server.start();

        final ConfigurationFactory<CassandraSchedulerConfiguration> factory =
                new ConfigurationFactory<>(
                        CassandraSchedulerConfiguration.class,
                        BaseValidator.newValidator(),
                        Jackson.newObjectMapper().registerModule(
                                new GuavaModule())
                                .registerModule(new Jdk8Module()),
                        "dw");

        config = factory.build(
                new SubstitutingSourceProvider(
                        new FileConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor(false)),
                Resources.getResource("scheduler.yml").getFile());

        Identity initial = config.getIdentity();

        persistence = (ZooKeeperPersistence) ZooKeeperPersistence.create(
                initial,
                CuratorFrameworkConfig.create(server.getConnectString(),
                        10000L,
                        10000L,
                        Optional.empty(),
                        250L));

        manager = new ConfigurationManager(
                config.getCassandraConfig(),
                config.getExecutorConfig(),
                config.getServers(),
                config.getSeeds(),
                false,
                "NODE",
                "INSTALL",
                config.getSeedsUrl(),
                persistence,
                CassandraConfig.JSON_SERIALIZER,
                ExecutorConfig.JSON_SERIALIZER,
                IntegerStringSerializer.get()
        );

        manager.start();

    }

    @AfterClass
    public static void afterAll() throws Exception {

        manager.stop();

        persistence.stop();

        server.close();

        server.stop();
    }

    @Test
    public void testGetServers() throws Exception {
        Integer servers = resources.client().target("/v1/config/servers").request()
                .get(Integer.class);
        System.out.println("servers = " + servers);
        assertEquals(config.getServers(), servers.intValue());
    }

    @Test
    public void testGetCassandraConfig() throws Exception {
        String cassandraConfig = resources.client().target("/v1/config/cassandra").request()
                .get(String.class);
        System.out.println("cassandra config = " + cassandraConfig);

        // The following assert fails:
        // Expected :{"version":"2.2.5","cpus":0.5,"memoryMb":4096,"diskMb":10240,"replaceIp":null, ...
        // Actual   :{"version":"2.2.5","cpus":0.5,"memoryMb":4096,"diskMb":10240,"replaceIp":{"present":false},...
        // because of Jackson's mishandling of Optional values:
        // https://stackoverflow.com/questions/25693309/using-jackson-objectmapper-with-java-8-optional-values
        //
        // Should we change Optional<String> replaceIp to String replaceIp?
        assertEquals(config.getCassandraConfig(), cassandraConfig);
    }

    @Test
    public void testGetExecutorConfig() throws Exception {
        ExecutorConfig executorConfig = resources.client().target("/v1/config/executor").request()
                .get(ExecutorConfig.class);
        System.out.println("executor config = " + executorConfig);
        assertEquals(config.getExecutorConfig(), executorConfig);
    }

    @Test
    public void testGetSeeds() throws Exception {
        Integer seeds = resources.client().target("/v1/config/seeds").request()
                .get(Integer.class);
        System.out.println("seeds = " + seeds);
        assertEquals(config.getSeeds(), seeds.intValue());
    }
}
