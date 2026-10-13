import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import '../samvad_app.dart';
import 'chat_screen.dart';

class ChatListScreen extends StatefulWidget {
  const ChatListScreen({super.key});

  @override
  State<ChatListScreen> createState() => _ChatListScreenState();
}

class _ChatListScreenState extends State<ChatListScreen> {
  int _selectedTab = 0;
  final List<String> _tabs = ['All', 'Personal', 'Groups'];
  List<Chat> _chats = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadChats();
    _setupSocketListeners();
  }

  Future<void> _loadChats() async {
    setState(() => _isLoading = true);
    final chats = await ApiService.getChats();
    if (mounted) {
      setState(() {
        _chats = chats;
        _isLoading = false;
      });
    }
  }

  void _setupSocketListeners() {
    // To be implemented with Supabase in Phase 8
  }

  List<Chat> get _filteredChats {
    if (_selectedTab == 0) return _chats;
    if (_selectedTab == 1) return _chats.where((chat) => !chat.isGroup).toList();
    return _chats.where((chat) => chat.isGroup).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: SamvadColors.background,
      body: SafeArea(
        child: Column(
          children: [
            // Header
            _buildHeader(),
            
            // Tabs
            _buildTabs(),

            // Chat List
            Expanded(
              child: _isLoading 
                ? const Center(child: CircularProgressIndicator(color: SamvadColors.primary))
                : _chats.isEmpty 
                  ? _buildEmptyState()
                  : RefreshIndicator(
                      onRefresh: _loadChats,
                      color: SamvadColors.primary,
                      child: ListView.builder(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        itemCount: _filteredChats.length,
                        itemBuilder: (context, index) {
                          final chat = _filteredChats[index];
                          return ChatListItem(chat: chat)
                              .animate()
                              .fadeIn(delay: (index * 50).ms)
                              .slideY(begin: 0.1, end: 0);
                        },
                      ),
                    ),
            ),
          ],
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          // Implement new chat / contact selection
        },
        backgroundColor: SamvadColors.primary,
        elevation: 8,
        child: const Icon(Icons.add_comment_rounded, color: Colors.white),
      ).animate().scale(delay: 400.ms),
    );
  }

  Widget _buildHeader() {
    return Container(
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          const SamvadLogo(size: 40),
          const SizedBox(width: 12),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Samvad',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                    letterSpacing: 1,
                  ),
                ),
                Text(
                  'संवाद Messenger',
                  style: TextStyle(
                    fontSize: 12,
                    color: SamvadColors.onSurfaceMuted,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            onPressed: () {},
            icon: const Icon(Icons.search, color: Colors.white70),
          ),
          IconButton(
            onPressed: () {
              Navigator.pushNamed(context, '/settings');
            },
            icon: const Icon(Icons.settings_outlined, color: Colors.white70),
          ),
        ],
      ),
    );
  }

  Widget _buildTabs() {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: SamvadColors.surface,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: _tabs.asMap().entries.map((entry) {
          final index = entry.key;
          final isSelected = _selectedTab == index;
          return Expanded(
            child: GestureDetector(
              onTap: () => setState(() => _selectedTab = index),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: BoxDecoration(
                  color: isSelected ? SamvadColors.primary : Colors.transparent,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  entry.value,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    color: isSelected ? Colors.white : SamvadColors.onSurfaceMuted,
                    fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                  ),
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.chat_bubble_outline_rounded, size: 80, color: Colors.white.withOpacity(0.1)),
          const SizedBox(height: 24),
          const Text(
            'No Conversations Yet',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
          ),
          const SizedBox(height: 8),
          Text(
            'Start your first conversation on Samvad.',
            style: TextStyle(color: Colors.white.withOpacity(0.5)),
          ),
          const SizedBox(height: 32),
          ElevatedButton(
            onPressed: () {},
            child: const Text('New Chat'),
          ),
        ],
      ),
    );
  }
}

class ChatListItem extends StatelessWidget {
  final Chat chat;

  const ChatListItem({super.key, required this.chat});

  @override
  Widget build(BuildContext context) {
    final lastMsg = chat.lastMessage?.content ?? 'Start a conversation';
    final time = chat.lastMessageTime != null 
        ? '${chat.lastMessageTime!.hour}:${chat.lastMessageTime!.minute.toString().padLeft(2, '0')}'
        : '';

    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
      leading: Stack(
        children: [
          CircleAvatar(
            radius: 28,
            backgroundColor: SamvadColors.surface,
            backgroundImage: chat.participant.profilePicture != null 
                ? NetworkImage(chat.participant.profilePicture!) 
                : null,
            child: chat.participant.profilePicture == null 
                ? Text(
                    chat.participant.name[0].toUpperCase(),
                    style: const TextStyle(color: SamvadColors.primary, fontWeight: FontWeight.bold, fontSize: 20),
                  )
                : null,
          ),
          if (chat.participant.isOnline)
            Positioned(
              right: 0,
              bottom: 0,
              child: Container(
                width: 14,
                height: 14,
                decoration: BoxDecoration(
                  color: SamvadColors.success,
                  shape: BoxShape.circle,
                  border: Border.all(color: SamvadColors.background, width: 2),
                ),
              ),
            ),
        ],
      ),
      title: Text(
        chat.participant.name,
        style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.white, fontSize: 16),
      ),
      subtitle: Text(
        lastMsg,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(color: Colors.white.withOpacity(0.6), fontSize: 14),
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Text(time, style: TextStyle(color: Colors.white.withOpacity(0.4), fontSize: 12)),
          if (chat.unreadCount > 0)
            Container(
              margin: const EdgeInsets.only(top: 6),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: SamvadColors.primary,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                chat.unreadCount.toString(),
                style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.bold),
              ),
            ),
        ],
      ),
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(builder: (context) => ChatScreen(chat: chat)),
        );
      },
    );
  }
}
