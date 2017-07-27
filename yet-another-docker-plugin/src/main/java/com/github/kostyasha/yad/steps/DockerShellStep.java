package com.github.kostyasha.yad.steps;

import com.github.kostyasha.yad.DockerConnector;
import com.github.kostyasha.yad.DockerContainerLifecycle;
import com.github.kostyasha.yad.commons.DockerCreateContainer;
import com.github.kostyasha.yad.connector.YADockerConnector;
import com.github.kostyasha.yad.utils.ResolveVarFunction;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.DockerClient;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.command.StartContainerCmd;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.exception.NotFoundException;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.api.model.Frame;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.core.command.AttachContainerResultCallback;
import com.github.kostyasha.yad_docker_java.com.github.dockerjava.core.command.WaitContainerResultCallback;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import jenkins.model.CoreEnvironmentContributor;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.isNull;
import static org.apache.commons.codec.binary.Base64.encodeBase64;

/**
 * @author Kanstantsin Shautsou
 */
public class DockerShellStep extends Builder implements SimpleBuildStep {
    private static final Logger LOG = LoggerFactory.getLogger(DockerShellStep.class);

    private YADockerConnector connector = null;
    private DockerContainerLifecycle containerLifecycle = new DockerContainerLifecycle();

    @DataBoundConstructor
    public DockerShellStep() {
    }

    public YADockerConnector getConnector() {
        return connector;
    }

    @DataBoundSetter
    public void setConnector(YADockerConnector connector) {
        this.connector = connector;
    }

    public DockerContainerLifecycle getContainerLifecycle() {
        return containerLifecycle;
    }

    @DataBoundSetter
    public void setContainerLifecycle(DockerContainerLifecycle containerLifecycle) {
        this.containerLifecycle = containerLifecycle;
    }

    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        PrintStream llog = listener.getLogger();
        final String imageId = containerLifecycle.getImage();

        try (DockerClient client = (connector instanceof DockerConnector) ?
                ((DockerConnector) connector).getFreshClient() :
                connector.getClient()) {
            //pull image
            llog.println("Pulling image " + imageId + "...");
            containerLifecycle.getPullImage().exec(client, imageId, listener);

            llog.println("Trying to create container for " + imageId);
            LOG.info("Trying to create container for {}", imageId);
            final DockerCreateContainer createContainer = containerLifecycle.getCreateContainer();
            CreateContainerCmd containerConfig = client.createContainerCmd(imageId);
            // template specific options
            createContainer.fillContainerConfig(containerConfig, new ResolveVarFunction(run, listener));


            // mark specific options
            appendContainerConfig(run, listener, containerConfig, connector);

            addRunVars(run, listener, containerConfig);
            insertLabels(containerConfig);

            containerConfig
                    .withAttachStdout(true)
                    .withAttachStderr(true)
//                    .withStdinOpen(false)
                    .withTty(false);

            // create
            CreateContainerResponse createResp = containerConfig.exec();
            String cId = createResp.getId();

            llog.println("Created container " + cId + ", for " + run.getDisplayName());
            LOG.debug("Created container {}, for {}", cId, run.getDisplayName());

            try {
                // start
                StartContainerCmd startCommand = client.startContainerCmd(cId);
                startCommand.exec();
                llog.println("Started container " + cId);
                LOG.debug("Start container {}, for {}", cId, run.getDisplayName());


                try (AttachContainerResultCallback callback = new AttachContainerResultCallback() {
                    @Override
                    public void onNext(Frame frame) {
                        super.onNext(frame);
                        llog.print(new String(frame.getPayload()));
                    }
                }) {
                    client.attachContainerCmd(cId)
                            .withStdErr(true)
                            .withStdOut(true)
                            .withFollowStream(true)
                            .withLogs(true)
                            .exec(callback);

                    WaitContainerResultCallback waitCallback = new WaitContainerResultCallback();
                    client.waitContainerCmd(cId).exec(waitCallback);
                    final Integer statusCode = waitCallback.awaitStatusCode();
                    callback.awaitCompletion(1, TimeUnit.SECONDS);
                    if (isNull(statusCode) || statusCode != 0) {
                        throw new AbortException("Shell execution failed. Exit code: " + statusCode);
                    }
                }
            } catch (AbortException ae) {
                throw ae;
            } catch (Exception ex) {
                llog.println("failed to start cmd");
                throw ex;
            } finally {
                llog.println("Removing container " + cId);
                LOG.debug("Removing container {}, for {}", cId, run.getDisplayName());
                try {
                    containerLifecycle.getRemoveContainer().exec(client, cId);
                    llog.println("Removed container: " + cId);
                } catch (NotFoundException ex) {
                    llog.println("Already removed container: " + cId);
                } catch (Exception ex) {
                    //ignore ex
                }
            }
        } catch (AbortException ae) {
            throw ae;
        } catch (Exception ex) {
            LOG.error("", ex);
            llog.println("failed to start cmd");
            throw new IOException(ex);
        }
    }

    protected void appendContainerConfig(Run<?, ?> run, TaskListener listener, CreateContainerCmd containerConfig,
                                       YADockerConnector connector) {
    }

    protected static void addRunVars(Run run, TaskListener listener,
                                     CreateContainerCmd containerConfig) {
        // add job vars into shell env vars
        try {
            final List<String> envList = isNull(containerConfig.getEnv()) ?
                    new ArrayList<>() : Arrays.asList(containerConfig.getEnv());

            final EnvVars environment = getEnvVars(run, listener);
            // maybe something should be escaped
            environment.forEach((k, v) -> {
                envList.add(k + "=" + v);
            });
            containerConfig.withEnv(envList);
        } catch (IOException | InterruptedException e) {
            LOG.warn("Issues with resolving var", e);
        }
    }

    /**
     * Return all job related vars without executor vars.
     * I.e. slave is running in osx, but docker image for shell is linux.
     */
    protected static EnvVars getEnvVars(Run run, TaskListener listener) throws IOException, InterruptedException {
        final EnvVars envVars = run.getCharacteristicEnvVars();

        // from run.getEnvironment(listener) but without computer vars
        for (EnvironmentContributor ec : EnvironmentContributor.all().reverseView()) {
            // job vars
            ec.buildEnvironmentFor(run.getParent(), envVars, listener);

            // build vars
            if (ec instanceof CoreEnvironmentContributor) {
                // exclude executor computer related vars
                envVars.put("BUILD_DISPLAY_NAME", run.getDisplayName());

                String rootUrl = Jenkins.getInstance().getRootUrl();
                if (rootUrl != null) {
                    envVars.put("BUILD_URL", rootUrl + run.getUrl());
                }

                // and remove useless job var from CoreEnvironmentContributor
                envVars.remove("JENKINS_HOME");
                envVars.remove("HUDSON_HOME");
            } else {
                ec.buildEnvironmentFor(run, envVars, listener); // build vars
            }
        }


        return envVars;
    }

    /**
     * Append some tags to identify who create this container.
     */
    protected void insertLabels(CreateContainerCmd containerConfig) {
        // add tags
        Map<String, String> labels = containerConfig.getLabels();
        if (labels == null) labels = new HashMap<>();

        labels.put("YAD_PLUGIN", DockerShellStep.class.getName());
        labels.put("JENKINS_INSTANCE_IDENTITY",
                new String(encodeBase64(InstanceIdentity.get().getPublic().getEncoded()), UTF_8));

        containerConfig.withLabels(labels);
    }

    @Extension
    @Symbol("dockerShell")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}