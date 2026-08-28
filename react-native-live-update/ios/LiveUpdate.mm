#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

// Swift owns the implementation; this is the bridge's view of it. The legacy
// bridge macros are deliberate: they work unchanged under both the old
// architecture and the new one's interop layer, which is what lets a single
// build of this package support RN 0.68 through 0.83+.
@interface RCT_EXTERN_MODULE (LiveUpdate, RCTEventEmitter)

RCT_EXTERN_METHOD(isSupported
                  : (RCTPromiseResolveBlock)resolve withRejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getCapabilities
                  : (RCTPromiseResolveBlock)resolve withRejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(configureNotifications
                  : (NSDictionary *)config resolver
                  : (RCTPromiseResolveBlock)resolve rejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(start
                  : (NSString *)id name
                  : (NSString *)name content
                  : (NSDictionary *)content options
                  : (NSDictionary *)options resolver
                  : (RCTPromiseResolveBlock)resolve rejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(update
                  : (NSString *)id content
                  : (NSDictionary *)content resolver
                  : (RCTPromiseResolveBlock)resolve rejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(end
                  : (NSString *)id dismissAfterMs
                  : (nonnull NSNumber *)dismissAfterMs resolver
                  : (RCTPromiseResolveBlock)resolve rejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(getRunning
                  : (RCTPromiseResolveBlock)resolve withRejecter
                  : (RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(endAll
                  : (RCTPromiseResolveBlock)resolve withRejecter
                  : (RCTPromiseRejectBlock)reject)

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

@end
