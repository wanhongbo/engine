// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.dart;

import android.content.res.AssetManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import java.nio.ByteBuffer;

import io.flutter.Log;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.StringCodec;
import io.flutter.view.FlutterCallbackInformation;

/**
 * Configures, bootstraps, and starts executing Dart code.
 * <p>
 * WARNING: THIS CLASS IS EXPERIMENTAL. DO NOT SHIP A DEPENDENCY ON THIS CODE.
 * IF YOU USE IT, WE WILL BREAK YOU.
 * <p>
 * To specify a top-level Dart function to execute, use a {@link DartEntrypoint} to tell
 * {@link DartExecutor} where to find the Dart code to execute, and which Dart function to use as
 * the entrypoint. To execute the entrypoint, pass the {@link DartEntrypoint} to
 * {@link #executeDartEntrypoint(DartEntrypoint)}.
 * <p>
 * To specify a Dart callback to execute, use a {@link DartCallback}. A given Dart callback must
 * be registered with the Dart VM to be invoked by a {@link DartExecutor}. To execute the callback,
 * pass the {@link DartCallback} to {@link #executeDartCallback(DartCallback)}.
 * TODO(mattcarroll): add a reference to docs about background/plugin execution
 * <p>
 * Once started, a {@link DartExecutor} cannot be stopped. The associated Dart code will execute
 * until it completes, or until the {@link io.flutter.embedding.engine.FlutterEngine} that owns
 * this {@link DartExecutor} is destroyed.
 */
public class DartExecutor implements BinaryMessenger {
  private static final String TAG = "DartExecutor";

  @NonNull
  private final FlutterJNI flutterJNI;
  @NonNull
  private final DartMessenger messenger;
  private boolean isApplicationRunning = false;
  @Nullable
  private String isolateServiceId;
  @Nullable
  private IsolateServiceIdListener isolateServiceIdListener;

  private final BinaryMessenger.BinaryMessageHandler isolateChannelMessageHandler =
      new BinaryMessenger.BinaryMessageHandler() {
        @Override
        public void onMessage(ByteBuffer message, final BinaryReply callback) {
          isolateServiceId = StringCodec.INSTANCE.decodeMessage(message);
          if (isolateServiceIdListener != null) {
            isolateServiceIdListener.onIsolateServiceIdAvailable(isolateServiceId);
          }
        }
      };

  public DartExecutor(@NonNull FlutterJNI flutterJNI) {
    this.flutterJNI = flutterJNI;
    this.messenger = new DartMessenger(flutterJNI);
    messenger.setMessageHandler("flutter/isolate", isolateChannelMessageHandler);
  }

  /**
   * Invoked when the {@link io.flutter.embedding.engine.FlutterEngine} that owns this
   * {@link DartExecutor} attaches to JNI.
   * <p>
   * When attached to JNI, this {@link DartExecutor} begins handling 2-way communication to/from
   * the Dart execution context. This communication is facilitate via 2 APIs:
   * <ul>
   *  <li>{@link BinaryMessenger}, which sends messages to Dart</li>
   *  <li>{@link PlatformMessageHandler}, which receives messages from Dart</li>
   * </ul>
   */
  public void onAttachedToJNI() {
    Log.v(TAG, "Attached to JNI. Registering the platform message handler for this Dart execution context.");
    flutterJNI.setPlatformMessageHandler(messenger);
  }

  /**
   * Invoked when the {@link io.flutter.embedding.engine.FlutterEngine} that owns this
   * {@link DartExecutor} detaches from JNI.
   * <p>
   * When detached from JNI, this {@link DartExecutor} stops handling 2-way communication to/from
   * the Dart execution context.
   */
  public void onDetachedFromJNI() {
    Log.v(TAG, "Detached from JNI. De-registering the platform message handler for this Dart execution context.");
    flutterJNI.setPlatformMessageHandler(null);
  }

  /**
   * Is this {@link DartExecutor} currently executing Dart code?
   *
   * @return true if Dart code is being executed, false otherwise
   */
  public boolean isExecutingDart() {
    return isApplicationRunning;
  }

  /**
   * Starts executing Dart code based on the given {@code dartEntrypoint}.
   * <p>
   * See {@link DartEntrypoint} for configuration options.
   *
   * @param dartEntrypoint specifies which Dart function to run, and where to find it
   */
  public void executeDartEntrypoint(@NonNull DartEntrypoint dartEntrypoint) {
    if (isApplicationRunning) {
      Log.w(TAG, "Attempted to run a DartExecutor that is already running.");
      return;
    }

    Log.v(TAG, "Executing Dart entrypoint: " + dartEntrypoint);

    flutterJNI.runBundleAndSnapshotFromLibrary(
        dartEntrypoint.pathToBundle,
        dartEntrypoint.dartEntrypointFunctionName,
        null,
        dartEntrypoint.androidAssetManager
    );

    isApplicationRunning = true;
  }

  /**
   * Starts executing Dart code based on the given {@code dartCallback}.
   * <p>
   * See {@link DartCallback} for configuration options.
   *
   * @param dartCallback specifies which Dart callback to run, and where to find it
   */
  public void executeDartCallback(@NonNull DartCallback dartCallback) {
    if (isApplicationRunning) {
      Log.w(TAG, "Attempted to run a DartExecutor that is already running.");
      return;
    }

    Log.v(TAG, "Executing Dart callback: " + dartCallback);

    flutterJNI.runBundleAndSnapshotFromLibrary(
        dartCallback.pathToBundle,
        dartCallback.callbackHandle.callbackName,
        dartCallback.callbackHandle.callbackLibraryPath,
        dartCallback.androidAssetManager
    );

    isApplicationRunning = true;
  }

  //------ START BinaryMessenger -----

  /**
   * Sends the given {@code message} from Android to Dart over the given {@code channel}.
   *
   * @param channel the name of the logical channel used for the message.
   * @param message the message payload, a direct-allocated {@link ByteBuffer} with the message bytes
   */
  @Override
  @UiThread
  public void send(@NonNull String channel, @Nullable ByteBuffer message) {
    messenger.send(channel, message, null);
  }

  /**
   * Sends the given {@code messages} from Android to Dart over the given {@code channel} and
   * then has the provided {@code callback} invoked when the Dart side responds.
   *
   * @param channel  the name of the logical channel used for the message.
   * @param message  the message payload, a direct-allocated {@link ByteBuffer} with the message bytes
   *                 between position zero and current position, or null.
   * @param callback a callback invoked when the Dart application responds to the message
   */
  @Override
  @UiThread
  public void send(@NonNull String channel, @Nullable ByteBuffer message, @Nullable BinaryMessenger.BinaryReply callback) {
    messenger.send(channel, message, callback);
  }

  /**
   * Sets the given {@link io.flutter.plugin.common.BinaryMessenger.BinaryMessageHandler} as the
   * singular handler for all incoming messages received from the Dart side of this Dart execution
   * context.
   *
   * @param channel the name of the channel.
   * @param handler a {@link BinaryMessageHandler} to be invoked on incoming messages, or null.
   */
  @Override
  @UiThread
  public void setMessageHandler(@NonNull String channel, @Nullable BinaryMessenger.BinaryMessageHandler handler) {
    messenger.setMessageHandler(channel, handler);
  }
  //------ END BinaryMessenger -----

  /**
   * Returns an identifier for this executor's primary isolate.  This identifier can be used
   * in queries to the Dart service protocol.
   */
  @Nullable
  public String getIsolateServiceId() {
    return isolateServiceId;
  }

  /**
   * Callback interface invoked when the isolate identifier becomes available.
   */
  interface IsolateServiceIdListener {
    void onIsolateServiceIdAvailable(@NonNull String isolateServiceId);
  }

  /**
   * Set a listener that will be notified when an isolate identifier is available for this
   * executor's primary isolate.
   */
  public void setIsolateServiceIdListener(@Nullable IsolateServiceIdListener listener) {
    isolateServiceIdListener = listener;
    if (isolateServiceIdListener != null && isolateServiceId != null) {
      isolateServiceIdListener.onIsolateServiceIdAvailable(isolateServiceId);
    }
  }

  /**
   * Configuration options that specify which Dart entrypoint function is executed and where
   * to find that entrypoint and other assets required for Dart execution.
   */
  public static class DartEntrypoint {
    /**
     * Standard Android AssetManager, provided from some {@code Context} or {@code Resources}.
     */
    @NonNull
    public final AssetManager androidAssetManager;

    /**
     * The path within the AssetManager where the app will look for assets.
     */
    @NonNull
    public final String pathToBundle;

    /**
     * The name of a Dart function to execute.
     */
    @NonNull
    public final String dartEntrypointFunctionName;

    public DartEntrypoint(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundle,
        @NonNull String dartEntrypointFunctionName
    ) {
      this.androidAssetManager = androidAssetManager;
      this.pathToBundle = pathToBundle;
      this.dartEntrypointFunctionName = dartEntrypointFunctionName;
    }

    @Override
    @NonNull
    public String toString() {
      return "DartEntrypoint( bundle path: " + pathToBundle + ", function: " + dartEntrypointFunctionName + " )";
    }
  }

  /**
   * Configuration options that specify which Dart callback function is executed and where
   * to find that callback and other assets required for Dart execution.
   */
  public static class DartCallback {
    /**
     * Standard Android AssetManager, provided from some {@code Context} or {@code Resources}.
     */
    public final AssetManager androidAssetManager;

    /**
     * The path within the AssetManager where the app will look for assets.
     */
    public final String pathToBundle;

    /**
     * A Dart callback that was previously registered with the Dart VM.
     */
    public final FlutterCallbackInformation callbackHandle;

    public DartCallback(
        @NonNull AssetManager androidAssetManager,
        @NonNull String pathToBundle,
        @NonNull FlutterCallbackInformation callbackHandle
    ) {
      this.androidAssetManager = androidAssetManager;
      this.pathToBundle = pathToBundle;
      this.callbackHandle = callbackHandle;
    }

    @Override
    @NonNull
    public String toString() {
      return "DartCallback( bundle path: " + pathToBundle
          + ", library path: " + callbackHandle.callbackLibraryPath
          + ", function: " + callbackHandle.callbackName + " )";
    }
  }
}
