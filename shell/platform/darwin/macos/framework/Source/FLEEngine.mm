// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#import "flutter/shell/platform/darwin/macos/framework/Headers/FLEEngine.h"
#import "flutter/shell/platform/darwin/macos/framework/Source/FLEEngine_Internal.h"

#include <vector>

#import "flutter/shell/platform/darwin/macos/framework/Source/FLEViewController_Internal.h"
#import "flutter/shell/platform/embedder/embedder.h"

static NSString* const kICUBundlePath = @"icudtl.dat";

/**
 * Private interface declaration for FLEEngine.
 */
@interface FLEEngine () <FlutterBinaryMessenger>

/**
 * Called by the engine to make the context the engine should draw into current.
 */
- (bool)engineCallbackOnMakeCurrent;

/**
 * Called by the engine to clear the context the engine should draw into.
 */
- (bool)engineCallbackOnClearCurrent;

/**
 * Called by the engine when the context's buffers should be swapped.
 */
- (bool)engineCallbackOnPresent;

/**
 * Makes the resource context the current context.
 */
- (bool)engineCallbackOnMakeResourceCurrent;

/**
 * Handles a platform message from the engine.
 */
- (void)engineCallbackOnPlatformMessage:(const FlutterPlatformMessage*)message;

@end

#pragma mark -

/**
 * `FlutterPluginRegistrar` implementation handling a single plugin.
 */
@interface FlutterEngineRegistrar : NSObject <FlutterPluginRegistrar>
- (instancetype)initWithPlugin:(nonnull NSString*)pluginKey
                 flutterEngine:(nonnull FLEEngine*)flutterEngine;
@end

@implementation FlutterEngineRegistrar {
  NSString* _pluginKey;
  FLEEngine* _flutterEngine;
}

- (instancetype)initWithPlugin:(NSString*)pluginKey flutterEngine:(FLEEngine*)flutterEngine {
  self = [super init];
  if (self) {
    _pluginKey = [pluginKey copy];
    _flutterEngine = flutterEngine;
  }
  return self;
}

#pragma mark - FlutterPluginRegistrar

- (id<FlutterBinaryMessenger>)messenger {
  return _flutterEngine.binaryMessenger;
}

- (NSView*)view {
  return _flutterEngine.viewController.view;
}

- (void)addMethodCallDelegate:(nonnull id<FlutterPlugin>)delegate
                      channel:(nonnull FlutterMethodChannel*)channel {
  [channel setMethodCallHandler:^(FlutterMethodCall* call, FlutterResult result) {
    [delegate handleMethodCall:call result:result];
  }];
}

@end

// Callbacks provided to the engine. See the called methods for documentation.
#pragma mark - Static methods provided to engine configuration

static bool OnMakeCurrent(FLEEngine* engine) {
  return [engine engineCallbackOnMakeCurrent];
}

static bool OnClearCurrent(FLEEngine* engine) {
  return [engine engineCallbackOnClearCurrent];
}

static bool OnPresent(FLEEngine* engine) {
  return [engine engineCallbackOnPresent];
}

static uint32_t OnFBO(FLEEngine* engine) {
  // There is currently no case where a different FBO is used, so no need to forward.
  return 0;
}

static bool OnMakeResourceCurrent(FLEEngine* engine) {
  return [engine engineCallbackOnMakeResourceCurrent];
}

static void OnPlatformMessage(const FlutterPlatformMessage* message, FLEEngine* engine) {
  [engine engineCallbackOnPlatformMessage:message];
}

#pragma mark - FLEEngine implementation

@implementation FLEEngine {
  // The embedding-API-level engine object.
  FlutterEngine _engine;

  // A mapping of channel names to the registered handlers for those channels.
  NSMutableDictionary<NSString*, FlutterBinaryMessageHandler>* _messageHandlers;
}

- (instancetype)init {
  return [self initWithViewController:nil];
}

- (instancetype)initWithViewController:(FLEViewController*)viewController {
  self = [super init];
  if (self != nil) {
    _viewController = viewController;
    _messageHandlers = [[NSMutableDictionary alloc] init];
  }
  return self;
}

- (void)dealloc {
  if (FlutterEngineShutdown(_engine) == kSuccess) {
    _engine = NULL;
  }
}

- (BOOL)launchEngineWithAssetsPath:(NSURL*)assets
              commandLineArguments:(NSArray<NSString*>*)arguments {
  if (_engine != NULL) {
    return NO;
  }

  const FlutterRendererConfig rendererConfig = {
      .type = kOpenGL,
      .open_gl.struct_size = sizeof(FlutterOpenGLRendererConfig),
      .open_gl.make_current = (BoolCallback)OnMakeCurrent,
      .open_gl.clear_current = (BoolCallback)OnClearCurrent,
      .open_gl.present = (BoolCallback)OnPresent,
      .open_gl.fbo_callback = (UIntCallback)OnFBO,
      .open_gl.make_resource_current = (BoolCallback)OnMakeResourceCurrent,
  };

  // TODO(stuartmorgan): Move internal channel registration from FLEViewController to here.

  // FlutterProjectArgs is expecting a full argv, so when processing it for flags the first
  // item is treated as the executable and ignored. Add a dummy value so that all provided arguments
  // are used.
  std::vector<const char*> argv = {"placeholder"};
  for (NSUInteger i = 0; i < arguments.count; ++i) {
    argv.push_back([arguments[i] UTF8String]);
  }

  NSString* icuData = [[NSBundle bundleForClass:[self class]] pathForResource:kICUBundlePath
                                                                       ofType:nil];

  FlutterProjectArgs flutterArguments = {};
  flutterArguments.struct_size = sizeof(FlutterProjectArgs);
  flutterArguments.assets_path = assets.fileSystemRepresentation;
  flutterArguments.icu_data_path = icuData.UTF8String;
  flutterArguments.command_line_argc = static_cast<int>(argv.size());
  flutterArguments.command_line_argv = &argv[0];
  flutterArguments.platform_message_callback = (FlutterPlatformMessageCallback)OnPlatformMessage;

  FlutterEngineResult result = FlutterEngineRun(
      FLUTTER_ENGINE_VERSION, &rendererConfig, &flutterArguments, (__bridge void*)(self), &_engine);
  if (result != kSuccess) {
    NSLog(@"Failed to start Flutter engine: error %d", result);
    return NO;
  }
  return YES;
}

- (id<FlutterBinaryMessenger>)binaryMessenger {
  // TODO(stuartmorgan): Switch to FlutterBinaryMessengerRelay to avoid plugins
  // keeping the engine alive.
  return self;
}

#pragma mark - Framework-internal methods

- (void)updateWindowMetricsWithSize:(CGSize)size pixelRatio:(double)pixelRatio {
  const FlutterWindowMetricsEvent event = {
      .struct_size = sizeof(event),
      .width = static_cast<size_t>(size.width),
      .height = static_cast<size_t>(size.height),
      .pixel_ratio = pixelRatio,
  };
  FlutterEngineSendWindowMetricsEvent(_engine, &event);
}

- (void)sendPointerEvent:(const FlutterPointerEvent&)event {
  FlutterEngineSendPointerEvent(_engine, &event, 1);
}

#pragma mark - Private methods

- (bool)engineCallbackOnMakeCurrent {
  if (!_viewController.view) {
    return false;
  }
  [_viewController.view makeCurrentContext];
  return true;
}

- (bool)engineCallbackOnClearCurrent {
  if (!_viewController.view) {
    return false;
  }
  [NSOpenGLContext clearCurrentContext];
  return true;
}

- (bool)engineCallbackOnPresent {
  if (!_viewController.view) {
    return false;
  }
  [_viewController.view onPresent];
  return true;
}

- (bool)engineCallbackOnMakeResourceCurrent {
  if (!_viewController.view) {
    return false;
  }
  [_viewController makeResourceContextCurrent];
  return true;
}

- (void)engineCallbackOnPlatformMessage:(const FlutterPlatformMessage*)message {
  NSData* messageData = [NSData dataWithBytesNoCopy:(void*)message->message
                                             length:message->message_size
                                       freeWhenDone:NO];
  NSString* channel = @(message->channel);
  __block const FlutterPlatformMessageResponseHandle* responseHandle = message->response_handle;

  FlutterBinaryReply binaryResponseHandler = ^(NSData* response) {
    if (responseHandle) {
      FlutterEngineSendPlatformMessageResponse(self->_engine, responseHandle,
                                               static_cast<const uint8_t*>(response.bytes),
                                               response.length);
      responseHandle = NULL;
    } else {
      NSLog(@"Error: Message responses can be sent only once. Ignoring duplicate response "
             "on channel '%@'.",
            channel);
    }
  };

  FlutterBinaryMessageHandler channelHandler = _messageHandlers[channel];
  if (channelHandler) {
    channelHandler(messageData, binaryResponseHandler);
  } else {
    binaryResponseHandler(nil);
  }
}

#pragma mark - FlutterBinaryMessenger

- (void)sendOnChannel:(nonnull NSString*)channel message:(nullable NSData*)message {
  FlutterPlatformMessage platformMessage = {
      .struct_size = sizeof(FlutterPlatformMessage),
      .channel = [channel UTF8String],
      .message = static_cast<const uint8_t*>(message.bytes),
      .message_size = message.length,
  };

  FlutterEngineResult result = FlutterEngineSendPlatformMessage(_engine, &platformMessage);
  if (result != kSuccess) {
    NSLog(@"Failed to send message to Flutter engine on channel '%@' (%d).", channel, result);
  }
}

- (void)setMessageHandlerOnChannel:(nonnull NSString*)channel
              binaryMessageHandler:(nullable FlutterBinaryMessageHandler)handler {
  _messageHandlers[channel] = [handler copy];
}

#pragma mark - FlutterPluginRegistry

- (id<FlutterPluginRegistrar>)registrarForPlugin:(NSString*)pluginName {
  return [[FlutterEngineRegistrar alloc] initWithPlugin:pluginName flutterEngine:self];
}

@end
