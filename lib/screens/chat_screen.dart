import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:emoji_picker_flutter/emoji_picker_flutter.dart';
import 'package:image_picker/image_picker.dart';
import '../samvad_app.dart';
import 'dart:async';

class ChatScreen extends StatefulWidget {
  final Chat chat;

  const ChatScreen({
    super.key,
    required this.chat,
  });

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final TextEditingController _messageController = TextEditingController();
  final ScrollController _scrollController = ScrollController();
  List<Message> _messages = [];
  bool _isLoading = true;
  bool _isTyping = false;
  Timer? _typingTimer;
  bool _showEmojiPicker = false;

  @override
  void initState() {
    super.initState();
    _loadMessages();
    _setupSocketListeners();
    _markMessagesAsSeen();
  }

  @override
  void dispose() {
    _messageController.dispose();
    _scrollController.dispose();
    _typingTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadMessages() async {
    final messages = await ApiService.getMessages(widget.chat.participant.id);
    if (mounted) {
      setState(() {
        _messages = messages;
        _isLoading = false;
      });
      _scrollToBottom();
    }
  }

  void _setupSocketListeners() {
    // Realtime to be implemented with Supabase in Phase 8
  }

  void _markMessagesAsSeen() {
    ApiService.markMessagesSeen(widget.chat.participant.id);
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _sendMessage() {
    if (_messageController.text.trim().isEmpty) return;
    final content = _messageController.text.trim();
    _messageController.clear();
    
    ApiService.sendMessage(
      conversationId: widget.chat.id,
      content: content,
    );
    _scrollToBottom();
  }

  void _onTypingChanged() {
    ApiService.sendTyping(widget.chat.participant.id, true);
    _typingTimer?.cancel();
    _typingTimer = Timer(const Duration(seconds: 2), () {
      ApiService.sendTyping(widget.chat.participant.id, false);
    });
  }

  Future<void> _pickImage() async {
    final ImagePicker picker = ImagePicker();
    final XFile? image = await picker.pickImage(source: ImageSource.gallery);
    if (image != null) {
      // Logic to upload image and send message
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: SamvadColors.background,
      appBar: AppBar(
        backgroundColor: SamvadColors.surface,
        elevation: 0,
        leading: IconButton(
          onPressed: () => Navigator.pop(context),
          icon: const Icon(Icons.arrow_back, color: Colors.white),
        ),
        title: Row(
          children: [
            CircleAvatar(
              radius: 18,
              backgroundColor: SamvadColors.primary,
              backgroundImage: widget.chat.participant.profilePicture != null 
                  ? NetworkImage(widget.chat.participant.profilePicture!) 
                  : null,
              child: widget.chat.participant.profilePicture == null 
                  ? Text(widget.chat.participant.name[0].toUpperCase(), style: const TextStyle(color: Colors.white, fontSize: 14))
                  : null,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.chat.participant.name,
                    style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
                  ),
                  Text(
                    _isTyping ? 'typing...' : (widget.chat.participant.isOnline ? 'Online' : 'Offline'),
                    style: TextStyle(
                      color: _isTyping || widget.chat.participant.isOnline ? SamvadColors.success : SamvadColors.onSurfaceMuted,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          IconButton(icon: const Icon(Icons.videocam, color: Colors.white70), onPressed: () {}),
          IconButton(icon: const Icon(Icons.call, color: Colors.white70), onPressed: () {}),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: _isLoading 
              ? const Center(child: CircularProgressIndicator(color: SamvadColors.primary))
              : ListView.builder(
                  controller: _scrollController,
                  padding: const EdgeInsets.all(20),
                  itemCount: _messages.length,
                  itemBuilder: (context, index) => MessageBubble(
                    message: _messages[index],
                    isMe: _messages[index].sender.id == ApiService.currentUser?.id,
                  ),
                ),
          ),
          _buildInputArea(),
          if (_showEmojiPicker) _buildEmojiPicker(),
        ],
      ),
    );
  }

  Widget _buildInputArea() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: const BoxDecoration(
        color: SamvadColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(32)),
      ),
      child: SafeArea(
        child: Row(
          children: [
            IconButton(
              icon: Icon(_showEmojiPicker ? Icons.keyboard : Icons.emoji_emotions, color: SamvadColors.primaryLight),
              onPressed: () => setState(() => _showEmojiPicker = !_showEmojiPicker),
            ),
            IconButton(
              icon: const Icon(Icons.attach_file, color: SamvadColors.primaryLight),
              onPressed: _pickImage,
            ),
            Expanded(
              child: TextField(
                controller: _messageController,
                onChanged: (_) => _onTypingChanged(),
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  hintText: 'Type a message...',
                  border: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  filled: false,
                ),
              ),
            ),
            IconButton(
              onPressed: _sendMessage,
              icon: Container(
                padding: const EdgeInsets.all(10),
                decoration: const BoxDecoration(
                  gradient: LinearGradient(colors: SamvadColors.gradientPrimary),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.send, color: Colors.white, size: 20),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmojiPicker() {
    return SizedBox(
      height: 250,
      child: EmojiPicker(
        onEmojiSelected: (category, emoji) {
          _messageController.text += emoji.emoji;
        },
        config: Config(
          bgColor: SamvadColors.background,
          indicatorColor: SamvadColors.primary,
          iconColorSelected: SamvadColors.primary,
          backspaceColor: SamvadColors.primary,
        ),
      ),
    );
  }
}

class MessageBubble extends StatelessWidget {
  final Message message;
  final bool isMe;

  const MessageBubble({
    super.key,
    required this.message,
    required this.isMe,
  });

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: isMe ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.only(bottom: 12),
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.75),
        decoration: BoxDecoration(
          color: isMe ? SamvadColors.messageSent : SamvadColors.messageReceived,
          borderRadius: BorderRadius.only(
            topLeft: const Radius.circular(20),
            topRight: const Radius.circular(20),
            bottomLeft: Radius.circular(isMe ? 20 : 0),
            bottomRight: Radius.circular(isMe ? 0 : 20),
          ),
          boxShadow: [
            if (isMe) 
              BoxShadow(
                color: SamvadColors.primary.withOpacity(0.3),
                blurRadius: 8,
                offset: const Offset(0, 4),
              )
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Text(
              message.content,
              style: const TextStyle(fontSize: 16, color: Colors.white),
            ),
            const SizedBox(height: 4),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '${message.timestamp.hour}:${message.timestamp.minute.toString().padLeft(2, '0')}',
                  style: const TextStyle(fontSize: 10, color: Colors.white54),
                ),
                if (isMe) ...[
                  const SizedBox(width: 4),
                  Icon(
                    message.status == 'seen' ? Icons.done_all : Icons.check,
                    size: 14,
                    color: message.status == 'seen' ? Colors.blueAccent : Colors.white54,
                  ),
                ],
              ],
            ),
          ],
        ),
      ).animate().fadeIn(duration: 300.ms).slideY(begin: 0.1, end: 0),
    );
  }
}
