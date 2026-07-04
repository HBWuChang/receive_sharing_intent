import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../receive_sharing_intent.dart';

class ReceiveSharingIntentMobile extends ReceiveSharingIntent {
  @visibleForTesting
  final mChannel = const MethodChannel('receive_sharing_intent/messages');

  @visibleForTesting
  final eChannelMedia =
      const EventChannel("receive_sharing_intent/events-media");

  static Stream<List<SharedMediaFile>>? _streamMedia;

  /// Helper: decode JSON array of share items, skip entries with no path.
  List<SharedMediaFile> _safeDecode(String json) {
    try {
      final encoded = jsonDecode(json) as List<dynamic>;
      final result = <SharedMediaFile>[];
      for (final item in encoded) {
        if (item is! Map) continue;
        if (item['path'] == null) {
          debugPrint('[receive_sharing_intent] Skipping share item: path is null');
          continue;
        }
        result.add(SharedMediaFile.fromMap(item.cast<String, dynamic>()));
      }
      return result;
    } catch (e, st) {
      debugPrint('[receive_sharing_intent] Failed to decode share data: $e\n$st');
      return [];
    }
  }

  @override
  Future<List<SharedMediaFile>> getInitialMedia() async {
    try {
      final json = await mChannel.invokeMethod('getInitialMedia');
      if (json == null) return [];
      return _safeDecode(json as String);
    } catch (e, st) {
      debugPrint('[receive_sharing_intent] getInitialMedia failed: $e\n$st');
      return [];
    }
  }

  @override
  Stream<List<SharedMediaFile>> getMediaStream() {
    if (_streamMedia == null) {
      final stream = eChannelMedia.receiveBroadcastStream().cast<String?>();
      _streamMedia = stream.transform<List<SharedMediaFile>>(
        StreamTransformer<String?, List<SharedMediaFile>>.fromHandlers(
          handleData: (data, sink) {
            if (data == null) {
              sink.add(<SharedMediaFile>[]);
            } else {
              sink.add(_safeDecode(data));
            }
          },
          handleError: (error, stack, sink) {
            debugPrint('[receive_sharing_intent] Stream error: $error\n$stack');
            sink.add(<SharedMediaFile>[]);
          },
        ),
      );
    }
    return _streamMedia!;
  }

  @override
  Future<dynamic> reset() {
    return mChannel.invokeMethod('reset');
  }
}
