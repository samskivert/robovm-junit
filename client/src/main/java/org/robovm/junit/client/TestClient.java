/*
 * Copyright (C) 2014 Trillian Mobile AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.robovm.junit.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.io.IOUtils;
import org.junit.runner.notification.RunListener;
import org.robovm.compiler.config.Arch;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.config.OS;
import org.robovm.compiler.plugin.LaunchPlugin;
import org.robovm.compiler.plugin.PluginArgument;
import org.robovm.compiler.plugin.PluginArguments;
import org.robovm.compiler.target.LaunchParameters;
import org.robovm.compiler.util.io.Fifos;
import org.robovm.compiler.util.io.OpenOnReadFileInputStream;
import org.robovm.compiler.util.io.OpenOnWriteFileOutputStream;
import org.robovm.junit.protocol.Command;
import org.robovm.junit.protocol.ResultObject;
import org.robovm.junit.protocol.ResultType;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.schedulers.Schedulers;

/**
 * Client side of the bridge between the tester (IDE, Maven, Gradle, etc) and
 * the testee (console, simulator, device).
 */
public class TestClient extends LaunchPlugin {

    private static class Waiter implements Runnable {
        CountDownLatch c = new CountDownLatch(1);
        public void run() {
            c.countDown();
        }
        public void await() throws InterruptedException {
            c.await();
        }
    }
    private static class Terminator extends Waiter {}
    
    public static final String SERVER_CLASS_NAME = "org.robovm.junit.server.TestServer";

    private Socket socket;
    private ServerPortReader serverPortReader;
    private File oldStdErr;
    private File newStdErr;
    private LinkedBlockingQueue<Object> runQueue = new LinkedBlockingQueue<>();
    private RunListener runListener;

    public TestClient() {
    }

    public TestClient runTests(String ... testsToRun) {
        runQueue.addAll(Arrays.asList(testsToRun));
        return this;
    }

    public void terminate() throws InterruptedException {
        Terminator t = new Terminator();
        runQueue.add(t);
        t.await();
    }

    public TestClient flush() throws InterruptedException {
        Waiter w = new Waiter();
        runQueue.add(w);
        w.await();
        return this;
    }
    
    public void setRunListener(RunListener runListener) {
        this.runListener = runListener;
    }
    
    @Override
    public void beforeLaunch(Config config, LaunchParameters parameters) {
        parameters.getArguments().add("-rvm:log=fatal");
        /*
         * Set this system property to true let the TestServer detect if it gets
         * restarted when running in the iOS simulator. See the comment in
         * TestServer for more info.
         */
        parameters.getArguments().add("-rvm:Drobovm.launchedFromTestClient=true");
        
        try {
            oldStdErr = parameters.getStderrFifo();
            newStdErr = Fifos.mkfifo("junit-err-proxy");
            parameters.setStderrFifo(newStdErr);
        } catch (IOException e) {
            throw new TestClientException("Couldn't create stderr pipe", e);
        }
    }

    @Override
    public void afterLaunch(Config config, LaunchParameters parameters, Process process) {
        try {
            serverPortReader = new ServerPortReader(config, parameters, process, oldStdErr, newStdErr);
            
            while (!serverPortReader.stopped && serverPortReader.port == -1) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }
            }
        } catch (Throwable t) {
            throw new TestClientException("Failed to get test server port", t);
        }

        // process stopped without providing a port
        if (serverPortReader.port == -1) {
            throw new TestClientException("Process stopped prematurely");
        }

        // Run the tests asynchronously
        runTests(config).subscribeOn(Schedulers.newThread()).subscribe(new Action1<ResultObject>() {
            public void call(ResultObject o) {
                try {
                    switch (o.getResultType()) {
                    case AssumptionFailure:
                        runListener.testAssumptionFailure(o.getFailure());
                        break;
                    case Failure:
                        runListener.testFailure(o.getFailure());
                        break;
                    case Finished:
                        runListener.testFinished(o.getDescription());
                        break;
                    case Ignored:
                        runListener.testIgnored(o.getDescription());
                        break;
                    case RunFinished:
                        runListener.testRunFinished(o.getResult());
                        break;
                    case RunStarted:
                        runListener.testRunStarted(o.getDescription());
                        break;
                    case Started:
                        runListener.testStarted(o.getDescription());
                        break;
                    default:
                        break;
                    }
                } catch (Exception e) {
                    // Swallow
                }
            }
        });
    }
    
    @Override
    public void cleanup() {
        if (serverPortReader != null) {
            serverPortReader.running = false;
            serverPortReader.thread.interrupt();
            serverPortReader = null;            
        }
    }
    
    @Override
    public PluginArguments getArguments() {
        return new PluginArguments("junit", Collections.<PluginArgument>emptyList());
    }
    
    @Override
    public void launchFailed(Config config, LaunchParameters parameters) {
    }
    
    private Observable<ResultObject> runTests(final Config config) {
        return Observable.create(new Observable.OnSubscribe<ResultObject>() {
            @Override
            public void call(Subscriber<? super ResultObject> subscriber) {
                try {
                    config.getLogger().debug("Trying to connect to test server running on port %d", serverPortReader.port);
                    socket = new Socket("localhost", serverPortReader.port);
                } catch (IOException e) {
                    if (config.getOs() == OS.ios && config.getArch() == Arch.thumbv7) {
                        subscriber.onError(new RuntimeException(
                                "Connection to device failed, check device logs for failure reason"));
                    } else {
                        subscriber.onError(new RuntimeException(
                                "Connection to test server failed"));
                    }
                    subscriber.onCompleted();
                    return;
                }

                config.getLogger().debug("Connected to test server at %s", socket.getRemoteSocketAddress());

                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));

                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                    String line = null;
                    Object action = null;
                    try {
                        while (!subscriber.isUnsubscribed() && (action = runQueue.take()) != null) {
                            if (action instanceof String) {
                                String testToRun = (String) action;
                                config.getLogger().debug("Running test %s", testToRun);
                                writer.write(Command.run + " " + testToRun + "\n");
                                writer.flush();
        
                                while ((line = reader.readLine()) != null) {
                                    ResultObject resultObject = ResultObject.fromJson(line);
                                    if (!subscriber.isUnsubscribed()) {
                                        subscriber.onNext(resultObject);
                                    }
                                    if (resultObject.getResultType() == ResultType.RunFinished) {
                                        break;
                                    }
                                }
                            } else if (action instanceof Terminator) {
                                ((Terminator) action).run();
                                break;
                            } else if (action instanceof Waiter) {
                                ((Waiter) action).run();
                            }
                        }
                    } catch (InterruptedException e) {}
                    subscriber.onCompleted();

                    config.getLogger().debug("Test run completed. Shutting down test server...");

                    writer.write(Command.terminate + "\n");
                    writer.flush();

                    writer.close();
                    socket.close();

                    config.getLogger().debug("Test client finished.");
                    
                } catch (IOException ie) {
                    subscriber.onError(ie);
                }
            }
        });
    }

    public Config.Builder configure(Config.Builder configBuilder) {
        if (configBuilder == null) {
            throw new IllegalArgumentException("RoboVM configuration cannot be null");
        }

        configBuilder.mainClass(SERVER_CLASS_NAME);
        configBuilder.addForceLinkClass("com.android.org.conscrypt.OpenSSLProvider");
        configBuilder.addForceLinkClass("com.android.org.conscrypt.OpenSSLMessageDigestJDK**");
        
        configBuilder.addLaunchPlugin(this);
        
        return configBuilder;
    }

    /**
     * Wraps the error stream of the runner and reads the port which the runner 
     * will be print to stderr. Will continue to wrap the error stream until the 
     * runner process has finished.
     */
    private static class ServerPortReader {
        volatile boolean running = false;
        volatile boolean stopped = false;
        volatile int port = -1;
        Thread thread;
        volatile boolean closeOutOnExit = true;

        public ServerPortReader(final Config config, final LaunchParameters params, final Process process,
                final File oldStdError, final File newStdError) throws IOException {

            final BufferedReader in = new BufferedReader(new InputStreamReader(new OpenOnReadFileInputStream(newStdError)));
            BufferedWriter writer = null;
            if (oldStdError != null) {
                writer = new BufferedWriter(new OutputStreamWriter(new OpenOnWriteFileOutputStream(oldStdError)));
            } else {
                writer = new BufferedWriter(new OutputStreamWriter(System.err));
                closeOutOnExit = false;
            }
            final BufferedWriter out = writer;

            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        running = true;
                        while (running && !isProcessStopped(process)) {
                            String line = in.readLine();
                            if (line != null) {
                                if (line.startsWith(SERVER_CLASS_NAME + ": port=")) {
                                    port = Integer.parseInt(line.split("=")[1]);
                                    config.getLogger().debug("Test runner port: " + port);
                                } else {
                                    out.write(line + "\n");
                                    out.flush();
                                }
                            }
                        }
                    } catch (Throwable t) {
                        config.getLogger().error("Couldn't forward error stream", t.getMessage());
                    } finally {
                        if (closeOutOnExit) {
                            IOUtils.closeQuietly(out);
                        }
                        IOUtils.closeQuietly(in);
                        stopped = true;
                    }
                }

                private boolean isProcessStopped(Process process) {
                    try {
                        process.exitValue();
                        return true;
                    } catch (IllegalThreadStateException e) {
                        return false;
                    }
                }
            });
            thread.setName("JUnit Port Reader");
            thread.setDaemon(true);
            thread.start();
        }
    }
}