import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'dart:io';
import 'package:permission_handler/permission_handler.dart';
import 'package:file_picker/file_picker.dart';
import 'package:image_picker/image_picker.dart';
import 'package:uuid/uuid.dart';
import 'package:path/path.dart' as p;
import 'package:rxdart/rxdart.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:device_info_plus/device_info_plus.dart';

// Import Screens
import 'screens/splash_screen.dart';
import 'screens/login_screen.dart';
import 'screens/chat_list_screen.dart';
import 'screens/profile_screen.dart';
import 'screens/settings_screen.dart';

// Supabase Configuration
const String supabaseUrl = 'YOUR_SUPABASE_URL';
const String supabaseAnonKey = 'YOUR_SUPABASE_ANON_KEY';

// Production API Configuration
class ApiConfig {
  static const String baseUrl = 'https://api.samvad.app'; 
  static const Duration timeout = Duration(seconds: 30);
  static const String wsUrl = 'wss://api.samvad.app';
}

// Samvad App Colors - Premium Unified Brand
class SamvadColors {
  static const primary = Color(0xFF4F46E5); // Deep Indigo
  static const primaryLight = Color(0xFF818CF8);
  static const secondary = Color(0xFF2563EB); // Royal Blue
  static const accent = Color(0xFF06B6D4); // Cyan
  static const background = Color(0xFF0F172A); // Deep Charcoal
  static const surface = Color(0xFF1E293B);
  static const onPrimary = Color(0xFFFFFFFF);
  static const onSecondary = Color(0xFFFFFFFF);
  static const onSurface = Color(0xFFF8FAFC);
  static const onSurfaceMuted = Color(0xFF94A3B8);
  static const border = Color(0xFF334155);
  static const messageSent = Color(0xFF4F46E5);
  static const messageReceived = Color(0xFF334155);
  static const error = Color(0xFFEF4444);
  static const success = Color(0xFF10B981);
  static const warning = Color(0xFFF59E0B);
  
  static const gradientPrimary = [Color(0xFF4F46E5), Color(0xFF2563EB)];
  static const gradientBackground = [Color(0xFF0F172A), Color(0xFF1E293B)];
}

// User Model
class User {
  final String id;
  final String uid;
  final String name;
  final String phone;
  final String countryCode;
  final String? email;
  final String? profilePicture;
  final String bio;
  final bool isOnline;
  final DateTime lastSeen;
  final String? fcmToken;

  User({
    required this.id,
    required this.uid,
    required this.name,
    required this.phone,
    required this.countryCode,
    this.email,
    this.profilePicture,
    required this.bio,
    required this.isOnline,
    required this.lastSeen,
    this.fcmToken,
  });

  factory User.fromJson(Map<String, dynamic> json) {
    return User(
      id: json['id'] ?? json['_id'] ?? '',
      uid: json['id'] ?? json['uid'] ?? '',
      name: json['display_name'] ?? json['name'] ?? 'Samvad User',
      phone: json['phone_number'] ?? json['phone'] ?? '',
      countryCode: json['country_code'] ?? json['countryCode'] ?? '+91',
      email: json['email'],
      profilePicture: json['avatar_url'] ?? json['profilePicture'],
      bio: json['bio'] ?? 'Available on Samvad Messenger',
      isOnline: json['online_status'] ?? json['isOnline'] ?? false,
      lastSeen: json['last_seen_at'] != null 
          ? DateTime.parse(json['last_seen_at']) 
          : (json['lastSeen'] != null ? DateTime.parse(json['lastSeen']) : DateTime.now()),
      fcmToken: json['fcm_token'] ?? json['fcmToken'],
    );
  }
}

// Message Model
class Message {
  final String id;
  final User sender;
  final User receiver;
  final String content;
  final String type;
  final String? fileUrl;
  final String? fileName;
  final int? fileSize;
  final DateTime timestamp;
  String status;
  final bool edited;
  final bool deleted;

  Message({
    required this.id,
    required this.sender,
    required this.receiver,
    required this.content,
    required this.type,
    this.fileUrl,
    this.fileName,
    this.fileSize,
    required this.timestamp,
    required this.status,
    required this.edited,
    required this.deleted,
  });

  factory Message.fromJson(Map<String, dynamic> json) {
    return Message(
      id: json['_id'] ?? '',
      sender: User.fromJson(json['senderId']),
      receiver: User.fromJson(json['receiverId']),
      content: json['content'] ?? '',
      type: json['type'] ?? 'text',
      fileUrl: json['fileUrl'],
      fileName: json['fileName'],
      fileSize: json['fileSize'],
      timestamp: json['timestamp'] != null ? DateTime.parse(json['timestamp']) : DateTime.now(),
      status: json['status'] ?? 'sent',
      edited: json['edited'] ?? false,
      deleted: json['deleted'] ?? false,
    );
  }
}

// Chat Model
class Chat {
  final String id;
  final User participant;
  final Message? lastMessage;
  final DateTime? lastMessageTime;
  final int unreadCount;
  final bool isGroup;
  final String? groupName;
  final String? groupDescription;
  final String? groupPicture;

  Chat({
    required this.id,
    required this.participant,
    this.lastMessage,
    this.lastMessageTime,
    required this.unreadCount,
    required this.isGroup,
    this.groupName,
    this.groupDescription,
    this.groupPicture,
  });

  factory Chat.fromJson(Map<String, dynamic> json) {
    return Chat(
      id: json['id'] ?? json['_id'] ?? '',
      participant: User.fromJson(json['participant']),
      lastMessage: json['lastMessage'] != null 
          ? Message.fromJson(json['lastMessage']) 
          : null,
      lastMessageTime: json['lastMessageTime'] != null 
          ? DateTime.parse(json['lastMessageTime']) 
          : null,
      unreadCount: json['unreadCount'] ?? 0,
      isGroup: json['isGroup'] ?? false,
      groupName: json['groupName'],
      groupDescription: json['groupDescription'],
      groupPicture: json['groupPicture'],
    );
  }
}

// API Service
// API & Authentication Service (Supabase Integration)
class ApiService {
  static final supabase = Supabase.instance.client;
  static User? _currentUser;
  
  // Realtime Channels & Controllers
  static RealtimeChannel? _presenceChannel;
  static RealtimeChannel? _messagingChannel;

  // RxDart streams to update UI globally without direct context
  static final BehaviorSubject<Map<String, dynamic>> _typingStatusController = BehaviorSubject<Map<String, dynamic>>.seeded({});
  static Stream<Map<String, dynamic>> get typingStatusStream => _typingStatusController.stream;

  static void setCurrentUser(User user) {
    _currentUser = user;
  }

  static User? get currentUser => _currentUser;

  // Format phone number to E.164 standard
  static String formatPhone(String phone, String countryCode) {
    final cleaned = phone.replaceAll(RegExp(r'\D'), '');
    final code = countryCode.startsWith('+') ? countryCode : '+$countryCode';
    return '$code$cleaned';
  }

  // 1. Phone OTP Auth
  static Future<Map<String, dynamic>> sendOTP({
    required String phone,
    String countryCode = '+91',
  }) async {
    try {
      final formattedPhone = formatPhone(phone, countryCode);
      await supabase.auth.signInWithOtp(
        phone: formattedPhone,
      );
      return {'success': true, 'message': 'OTP sent to $formattedPhone'};
    } on AuthException catch (e) {
      return {'success': false, 'message': e.message};
    } catch (e) {
      return {'success': false, 'message': 'Unexpected error: ${e.toString()}'};
    }
  }

  static Future<Map<String, dynamic>> verifyOTP({
    required String phone,
    required String countryCode,
    required String otp,
    String? name,
  }) async {
    try {
      final formattedPhone = formatPhone(phone, countryCode);
      final response = await supabase.auth.verifyOTP(
        phone: formattedPhone,
        token: otp,
        type: OtpType.sms,
      );

      if (response.user != null) {
        // Upsert user profile
        final userId = response.user!.id;
        final profileData = {
          'id': userId,
          'phone_number': formattedPhone,
          'country_code': countryCode,
          if (name != null && name.isNotEmpty) 'display_name': name,
          'updated_at': DateTime.now().toIso8601String(),
        };

        await supabase.from('profiles').upsert(profileData);
        await getUserProfile();
        return {'success': true, 'message': 'Authentication successful'};
      }
      return {'success': false, 'message': 'Invalid OTP response'};
    } on AuthException catch (e) {
      return {'success': false, 'message': e.message};
    } catch (e) {
      return {'success': false, 'message': 'Verification error: ${e.toString()}'};
    }
  }

  // 2. Email & Password Auth
  static Future<Map<String, dynamic>> signUpWithEmail({
    required String email,
    required String password,
    required String displayName,
  }) async {
    try {
      final response = await supabase.auth.signUp(
        email: email,
        password: password,
        data: {'display_name': displayName},
      );

      if (response.user != null) {
        final userId = response.user!.id;
        await supabase.from('profiles').upsert({
          'id': userId,
          'email': email,
          'display_name': displayName,
          'created_at': DateTime.now().toIso8601String(),
          'updated_at': DateTime.now().toIso8601String(),
        });
        await getUserProfile();
        return {'success': true, 'message': 'Account created successfully!'};
      }
      return {'success': false, 'message': 'Sign up failed'};
    } on AuthException catch (e) {
      return {'success': false, 'message': e.message};
    } catch (e) {
      return {'success': false, 'message': e.toString()};
    }
  }

  static Future<Map<String, dynamic>> signInWithEmail({
    required String email,
    required String password,
  }) async {
    try {
      final response = await supabase.auth.signInWithPassword(
        email: email,
        password: password,
      );

      if (response.user != null) {
        await getUserProfile();
        return {'success': true, 'message': 'Login successful'};
      }
      return {'success': false, 'message': 'Invalid credentials'};
    } on AuthException catch (e) {
      return {'success': false, 'message': e.message};
    } catch (e) {
      return {'success': false, 'message': e.toString()};
    }
  }

  static Future<Map<String, dynamic>> resetPassword(String email) async {
    try {
      await supabase.auth.resetPasswordForEmail(email);
      return {'success': true, 'message': 'Password reset email sent!'};
    } on AuthException catch (e) {
      return {'success': false, 'message': e.message};
    } catch (e) {
      return {'success': false, 'message': e.toString()};
    }
  }

  // 3. User Profile
  static Future<Map<String, dynamic>> getUserProfile() async {
    try {
      final user = supabase.auth.currentUser;
      if (user == null) return {'success': false, 'message': 'User not authenticated'};

      final data = await supabase
          .from('profiles')
          .select()
          .eq('id', user.id)
          .single();

      _currentUser = User.fromJson(data);
      return {'success': true, 'user': data};
    } catch (e) {
      return {'success': false, 'message': e.toString()};
    }
  }

  static Future<Map<String, dynamic>> updateUserProfile({
    String? name,
    String? bio,
    String? profilePicture,
    String? username,
  }) async {
    try {
      final user = supabase.auth.currentUser;
      if (user == null) return {'success': false, 'message': 'User not authenticated'};

      final updateData = {
        if (name != null) 'display_name': name,
        if (bio != null) 'bio': bio,
        if (profilePicture != null) 'avatar_url': profilePicture,
        if (username != null) 'username': username,
        'updated_at': DateTime.now().toIso8601String(),
      };

      await supabase
          .from('profiles')
          .update(updateData)
          .eq('id', user.id);

      await getUserProfile();
      return {'success': true, 'message': 'Profile updated successfully'};
    } catch (e) {
      return {'success': false, 'message': e.toString()};
    }
  }

  // 4. Session Persistence
  static Future<bool> loadStoredCredentials() async {
    final session = supabase.auth.currentSession;
    if (session != null) {
      final res = await getUserProfile();
      return res['success'] == true;
    }
    return false;
  }

  static Future<void> clearStoredCredentials() async {
    await PushNotificationService.removeToken();
    await supabase.auth.signOut();
    _currentUser = null;
    await _presenceChannel?.unsubscribe();
    await _messagingChannel?.unsubscribe();
  }

  // 6. Realtime 1-to-1 Messaging (Phase 8)
  
  static void initializeRealtime() {
    if (_currentUser == null) return;
    final userId = _currentUser!.id;

    // 1. Presence Channel (Online Status & Typing)
    _presenceChannel = supabase.channel('online-users');
    _presenceChannel!
        .onPresenceSync((payload) {
           // We can get online users here
        })
        .onPresenceJoin((payload) {
          // Handle user joining
        })
        .onPresenceLeave((payload) {
          // Handle user leaving
        })
        .onBroadcast(event: 'typing', callback: (payload) {
           if (payload['receiverId'] == userId) {
             _typingStatusController.add(payload);
           }
        })
        .subscribe((status, [error]) async {
          if (status == RealtimeSubscribeStatus.subscribed) {
            await _presenceChannel!.track({
              'user_id': userId,
              'online_at': DateTime.now().toIso8601String(),
            });
          }
        });

    // 2. Messaging Channel (Incoming Messages & Updates)
    _messagingChannel = supabase.channel('public:messages')
      .onPostgresChanges(
        event: PostgresChangeEvent.insert,
        schema: 'public',
        table: 'messages',
        filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq, 
            column: 'conversation_id', 
            // Needs to subscribe to conversations, handled dynamically in ChatScreen
            value: 'dummy' 
        ),
        callback: (PostgresChangePayload payload) {
           // Handled at ChatScreen level directly via Supabase Streams
        })
      .subscribe();
  }

  static Future<List<Chat>> getChats() async {
    try {
      final user = supabase.auth.currentUser;
      if (user == null) return [];

      // Query conversations that the user belongs to
      final response = await supabase
          .from('conversation_members')
          .select('''
            conversation_id,
            conversations (
              id,
              is_group,
              title,
              avatar_url,
              description
            )
          ''')
          .eq('user_id', user.id);

      List<Chat> chats = [];
      for (var item in response) {
        final conv = item['conversations'];
        
        // Find the other participant for 1-to-1 chats
        User participant;
        if (conv['is_group'] == false) {
           final otherMemberRes = await supabase
              .from('conversation_members')
              .select('profiles(*)')
              .eq('conversation_id', conv['id'])
              .neq('user_id', user.id)
              .maybeSingle();

           if (otherMemberRes != null && otherMemberRes['profiles'] != null) {
              participant = User.fromJson(otherMemberRes['profiles']);
           } else {
             continue; // Skip if no other participant found
           }
        } else {
           participant = _currentUser!; // For groups, we use dummy participant for now
        }

        // Get last message
        final lastMsgRes = await supabase
            .from('messages')
            .select()
            .eq('conversation_id', conv['id'])
            .order('created_at', ascending: false)
            .limit(1)
            .maybeSingle();

        Message? lastMessage;
        if (lastMsgRes != null) {
            // Need sender/receiver profiles for full Message object but skipping for speed in list
            lastMessage = Message(
              id: lastMsgRes['id'],
              sender: User(id: lastMsgRes['sender_id'], uid: '', name: '', phone: '', countryCode: '', bio: '', isOnline: false, lastSeen: DateTime.now()),
              receiver: User(id: '', uid: '', name: '', phone: '', countryCode: '', bio: '', isOnline: false, lastSeen: DateTime.now()),
              content: lastMsgRes['content'] ?? '',
              type: lastMsgRes['type'] ?? 'text',
              timestamp: DateTime.parse(lastMsgRes['created_at']),
              status: lastMsgRes['status'] ?? 'sent',
              edited: lastMsgRes['is_edited'] ?? false,
              deleted: lastMsgRes['is_deleted'] ?? false,
            );
        }

        chats.add(Chat(
          id: conv['id'],
          participant: participant,
          lastMessage: lastMessage,
          lastMessageTime: lastMessage?.timestamp,
          unreadCount: 0, // Implement read receipts later
          isGroup: conv['is_group'],
          groupName: conv['title'],
          groupPicture: conv['avatar_url'],
          groupDescription: conv['description']
        ));
      }

      // Sort chats by latest message
      chats.sort((a, b) {
        final timeA = a.lastMessageTime ?? DateTime.fromMillisecondsSinceEpoch(0);
        final timeB = b.lastMessageTime ?? DateTime.fromMillisecondsSinceEpoch(0);
        return timeB.compareTo(timeA);
      });

      return chats;
    } catch (e) {
      debugPrint('Error getting chats: $e');
      return [];
    }
  }

  static Future<List<Message>> getMessages(String conversationId) async {
    try {
      final response = await supabase
          .from('messages')
          .select('''
            *,
            sender:profiles!messages_sender_id_fkey(*)
          ''')
          .eq('conversation_id', conversationId)
          .order('created_at', ascending: true);

      return (response as List).map((data) {
        return Message(
          id: data['id'],
          sender: User.fromJson(data['sender']),
          receiver: User(id: '', uid: '', name: '', phone: '', countryCode: '', bio: '', isOnline: false, lastSeen: DateTime.now()), // Not needed on UI
          content: data['content'] ?? '',
          type: data['type'] ?? 'text',
          fileUrl: data['file_url'],
          fileName: data['file_name'],
          fileSize: data['file_size'],
          timestamp: DateTime.parse(data['created_at']),
          status: data['status'] ?? 'sent',
          edited: data['is_edited'] ?? false,
          deleted: data['is_deleted'] ?? false,
        );
      }).toList();
    } catch (e) {
      debugPrint('Error getting messages: $e');
      return [];
    }
  }

  static Future<void> sendMessage({
    required String conversationId,
    required String content,
    String type = 'text',
    String? fileUrl,
    String? fileName,
    int? fileSize,
  }) async {
     if (_currentUser == null) return;
     
     try {
       await supabase.from('messages').insert({
         'conversation_id': conversationId,
         'sender_id': _currentUser!.id,
         'content': content,
         'type': type,
         'file_url': fileUrl,
         'file_name': fileName,
         'file_size': fileSize,
         'status': 'sent'
       });
     } catch(e) {
       debugPrint('Send message error: $e');
     }
  }

  static Future<String?> getOrCreateConversation(String otherUserId) async {
     if (_currentUser == null) return null;

     try {
       // Check if conversation exists
       final response = await supabase.rpc('get_direct_conversation', params: {
          'user1_id': _currentUser!.id,
          'user2_id': otherUserId
       });

       if (response != null && response is String) {
         return response;
       }

       // Create new conversation
       final convRes = await supabase.from('conversations').insert({
         'is_group': false
       }).select().single();

       final newConvId = convRes['id'];

       // Add members
       await supabase.from('conversation_members').insert([
         {'conversation_id': newConvId, 'user_id': _currentUser!.id},
         {'conversation_id': newConvId, 'user_id': otherUserId}
       ]);

       return newConvId;

     } catch (e) {
        debugPrint('Create conversation error: $e');
        return null;
     }
  }

  static void markMessagesSeen(String conversationId) async {
     if (_currentUser == null) return;
     try {
       await supabase.from('messages')
          .update({'status': 'read'})
          .eq('conversation_id', conversationId)
          .neq('sender_id', _currentUser!.id)
          .eq('status', 'delivered');
     } catch (e) {
       // Ignore
     }
  }

  static void sendTyping(String receiverId, bool isTyping) {
    if (_presenceChannel != null && _currentUser != null) {
      _presenceChannel!.sendBroadcastMessage(
        event: 'typing',
        payload: {
          'userId': _currentUser!.id,
          'receiverId': receiverId,
          'isTyping': isTyping,
        }
      );
    }
  }
}

// Push Notifications Service (Phase 10)
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  debugPrint("Handling a background message: ${message.messageId}");
}

class PushNotificationService {
  static final FirebaseMessaging _firebaseMessaging = FirebaseMessaging.instance;
  static final FlutterLocalNotificationsPlugin _localNotificationsPlugin = FlutterLocalNotificationsPlugin();

  static Future<void> initialize() async {
    // Check if firebase is initialized
    try {
      await Firebase.initializeApp();
    } catch (e) {
      debugPrint('Firebase already initialized or error: $e');
    }

    FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

    // Request permissions
      NotificationSettings settings = await _firebaseMessaging.requestPermission(
        alert: true,
        badge: true,
        sound: true,
      );

    if (settings.authorizationStatus == AuthorizationStatus.authorized) {
      // Setup local notifications for foreground display
      const AndroidInitializationSettings initializationSettingsAndroid = AndroidInitializationSettings('@mipmap/ic_launcher');
      const DarwinInitializationSettings initializationSettingsIOS = DarwinInitializationSettings(
        requestAlertPermission: true,
        requestBadgePermission: true,
        requestSoundPermission: true,
      );
      const InitializationSettings initializationSettings = InitializationSettings(
        android: initializationSettingsAndroid,
        iOS: initializationSettingsIOS,
      );

      await _localNotificationsPlugin.initialize(
        initializationSettings,
        onDidReceiveNotificationResponse: (NotificationResponse details) {
           // Handle notification tap
           debugPrint('Notification tapped: ${details.payload}');
        }
      );

      // Listen to token refresh
      _firebaseMessaging.onTokenRefresh.listen((token) {
        _registerToken(token);
      });

      // Get initial token
      String? token = await _firebaseMessaging.getToken();
      if (token != null) {
        await _registerToken(token);
      }

      // Foreground message listener
      FirebaseMessaging.onMessage.listen((RemoteMessage message) {
        debugPrint('Got a message whilst in the foreground!');
        
        RemoteNotification? notification = message.notification;
        AndroidNotification? android = message.notification?.android;

        if (notification != null && android != null) {
          _localNotificationsPlugin.show(
            notification.hashCode,
            notification.title,
            notification.body,
            const NotificationDetails(
              android: AndroidNotificationDetails(
                'samvad_high_importance_channel', // channelId
                'High Importance Notifications', // channelName
                channelDescription: 'This channel is used for important notifications.',
                importance: Importance.max,
                priority: Priority.high,
                icon: '@mipmap/ic_launcher',
              ),
              iOS: DarwinNotificationDetails()
            ),
            payload: message.data.toString()
          );
        }
      });
    }
  }

  static Future<void> _registerToken(String token) async {
    final user = ApiService.currentUser;
    if (user == null) return;

    try {
      final deviceInfo = DeviceInfoPlugin();
      String deviceId = 'unknown';
      String platform = 'web';

      if (Platform.isAndroid) {
        final androidInfo = await deviceInfo.androidInfo;
        deviceId = androidInfo.id;
        platform = 'android';
      } else if (Platform.isIOS) {
        final iosInfo = await deviceInfo.iosInfo;
        deviceId = iosInfo.identifierForVendor ?? 'ios-unknown';
        platform = 'ios';
      }

      await ApiService.supabase.from('push_tokens').upsert({
        'user_id': user.id,
        'device_id': deviceId,
        'platform': platform,
        'token': token,
        'updated_at': DateTime.now().toIso8601String(),
      });
    } catch (e) {
      debugPrint('Error registering push token: $e');
    }
  }

  static Future<void> removeToken() async {
    final user = ApiService.currentUser;
    if (user == null) return;

    try {
      final deviceInfo = DeviceInfoPlugin();
      String deviceId = 'unknown';

      if (Platform.isAndroid) {
        final androidInfo = await deviceInfo.androidInfo;
        deviceId = androidInfo.id;
      } else if (Platform.isIOS) {
        final iosInfo = await deviceInfo.iosInfo;
        deviceId = iosInfo.identifierForVendor ?? 'ios-unknown';
      }

      await ApiService.supabase
        .from('push_tokens')
        .delete()
        .match({
           'user_id': user.id,
           'device_id': deviceId
        });
    } catch (e) {
      debugPrint('Error removing push token: $e');
    }
  }
}

// Permissions Service
class PermissionsService {
  static Future<bool> requestContactsPermission() async {
    var status = await Permission.contacts.status;
    if (status.isDenied) {
      status = await Permission.contacts.request();
    }
    return status.isGranted;
  }

  static Future<bool> requestNotificationPermission() async {
    var status = await Permission.notification.status;
    if (status.isDenied) {
      status = await Permission.notification.request();
    }
    return status.isGranted;
  }

  static Future<bool> requestStoragePermission() async {
    var status = await Permission.storage.status;
    if (status.isDenied) {
      status = await Permission.storage.request();
    }
    return status.isGranted;
  }
}

// Contacts Service Helper
class ContactsServiceHelper {
  static Future<List<dynamic>> getContacts() async {
    bool granted = await PermissionsService.requestContactsPermission();
    if (!granted) return [];
    
    // To be implemented with flutter_contacts
    return [];
  }
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  try {
    await Supabase.initialize(
      url: supabaseUrl,
      anonKey: supabaseAnonKey, // anonKey is used for supabase_flutter 2.x
    );
  } catch (e) {
    debugPrint('Supabase initialization error: $e');
  }
  
  await ApiService.loadStoredCredentials();
  await PushNotificationService.initialize();
  
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: SamvadColors.background,
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );
  
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  
  runApp(const SamvadApp());
}

class SamvadApp extends StatelessWidget {
  const SamvadApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Samvad Messenger',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorScheme: const ColorScheme.dark(
          primary: SamvadColors.primary,
          secondary: SamvadColors.secondary,
          surface: SamvadColors.surface,
          onPrimary: SamvadColors.onPrimary,
          onSecondary: SamvadColors.onSecondary,
          onSurface: SamvadColors.onSurface,
        ),
        fontFamily: 'Inter',
        scaffoldBackgroundColor: SamvadColors.background,
        appBarTheme: const AppBarTheme(
          backgroundColor: Colors.transparent,
          elevation: 0,
          centerTitle: false,
          titleTextStyle: TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
            color: SamvadColors.onSurface,
          ),
          systemOverlayStyle: SystemUiOverlayStyle.light,
        ),
        cardTheme: CardThemeData(
          color: SamvadColors.surface,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(24),
            side: const BorderSide(color: SamvadColors.border),
          ),
        ),
        elevatedButtonTheme: ElevatedButtonThemeData(
          style: ElevatedButton.styleFrom(
            backgroundColor: SamvadColors.primary,
            foregroundColor: SamvadColors.onPrimary,
            elevation: 8,
            shadowColor: SamvadColors.primary.withValues(alpha: 0.4),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(16),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
            textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
          ),
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: SamvadColors.surface,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(16),
            borderSide: const BorderSide(color: SamvadColors.border),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(16),
            borderSide: const BorderSide(color: SamvadColors.border),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(16),
            borderSide: const BorderSide(color: SamvadColors.primary, width: 2),
          ),
          contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
          hintStyle: const TextStyle(color: SamvadColors.onSurfaceMuted),
        ),
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const SplashScreen(),
        '/login': (context) => const LoginScreen(),
        '/home': (context) => const ChatListScreen(),
        '/settings': (context) => const SettingsScreen(),
        '/profile': (context) => const ProfileScreen(),
      },
    );
  }
}

// Samvad Logo Widget - Premium Identity
class SamvadLogo extends StatelessWidget {
  final double size;

  const SamvadLogo({
    super.key,
    this.size = 80,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: SamvadColors.gradientPrimary,
        ),
        boxShadow: [
          BoxShadow(
            color: SamvadColors.primary.withValues(alpha: 0.5),
            blurRadius: 30,
            spreadRadius: 2,
            offset: const Offset(0, 10),
          ),
        ],
      ),
      child: Center(
        child: Icon(
          Icons.forum_rounded,
          size: size * 0.6,
          color: Colors.white,
        ),
      ),
    );
  }
}
