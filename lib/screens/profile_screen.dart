import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import '../samvad_app.dart';

class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});

  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  User? _user;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadProfile();
  }

  Future<void> _loadProfile() async {
    await ApiService.getUserProfile();
    if (mounted) {
      setState(() {
        _user = ApiService.currentUser;
        _isLoading = false;
      });
    }
  }

  Future<void> _logout() async {
    await ApiService.clearStoredCredentials();
    if (mounted) {
      Navigator.pushNamedAndRemoveUntil(context, '/login', (route) => false);
    }
  }

  void _showEditProfileDialog() {
    final nameController = TextEditingController(text: _user?.name);
    final bioController = TextEditingController(text: _user?.bio);

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: SamvadColors.surface,
        title: const Text('Edit Profile', style: TextStyle(color: Colors.white)),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: nameController,
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  labelText: 'Display Name',
                  labelStyle: TextStyle(color: SamvadColors.onSurfaceMuted),
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: bioController,
                style: const TextStyle(color: Colors.white),
                decoration: const InputDecoration(
                  labelText: 'Bio',
                  labelStyle: TextStyle(color: SamvadColors.onSurfaceMuted),
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel', style: TextStyle(color: Colors.white70)),
          ),
          ElevatedButton(
            onPressed: () async {
              Navigator.pop(context);
              setState(() => _isLoading = true);
              final res = await ApiService.updateUserProfile(
                name: nameController.text.trim(),
                bio: bioController.text.trim(),
              );
              setState(() => _isLoading = false);
              if (res['success'] == true) {
                _loadProfile();
              }
            },
            child: const Text('Save'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: SamvadColors.background,
      appBar: AppBar(
        title: const Text('Samvad Profile'),
        actions: [
          IconButton(
            icon: const Icon(Icons.edit, color: Colors.white),
            onPressed: _showEditProfileDialog,
          ),
        ],
      ),
      body: _isLoading 
          ? const Center(child: CircularProgressIndicator(color: SamvadColors.primary))
          : SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                children: [
                  const SizedBox(height: 20),
                  // Avatar
                  Center(
                    child: Stack(
                      children: [
                        Container(
                          padding: const EdgeInsets.all(4),
                          decoration: const BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: LinearGradient(colors: SamvadColors.gradientPrimary),
                          ),
                          child: CircleAvatar(
                            radius: 60,
                            backgroundColor: SamvadColors.surface,
                            backgroundImage: _user?.profilePicture != null && _user!.profilePicture!.isNotEmpty
                                ? NetworkImage(_user!.profilePicture!) 
                                : null,
                            child: _user?.profilePicture == null || _user!.profilePicture!.isEmpty
                                ? Text(
                                    (_user?.name ?? 'U')[0].toUpperCase(),
                                    style: const TextStyle(fontSize: 40, fontWeight: FontWeight.bold, color: Colors.white),
                                  )
                                : null,
                          ),
                        ),
                        Positioned(
                          bottom: 0,
                          right: 4,
                          child: GestureDetector(
                            onTap: _showEditProfileDialog,
                            child: Container(
                              padding: const EdgeInsets.all(8),
                              decoration: const BoxDecoration(
                                color: SamvadColors.primary,
                                shape: BoxShape.circle,
                              ),
                              child: const Icon(Icons.edit, color: Colors.white, size: 20),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ).animate().scale(duration: 600.ms, curve: Curves.elasticOut),

                  const SizedBox(height: 32),

                  // Info Card
                  Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      color: SamvadColors.surface,
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: SamvadColors.border),
                    ),
                    child: Column(
                      children: [
                        _buildInfoTile('Name', _user?.name ?? 'Samvad User', Icons.person_outline),
                        if (_user?.email != null && _user!.email!.isNotEmpty) ...[
                          const Divider(color: SamvadColors.border, height: 32),
                          _buildInfoTile('Email', _user!.email!, Icons.email_outlined),
                        ],
                        if (_user?.phone != null && _user!.phone.isNotEmpty) ...[
                          const Divider(color: SamvadColors.border, height: 32),
                          _buildInfoTile('Phone', '${_user?.countryCode} ${_user?.phone}', Icons.phone_outlined),
                        ],
                        const Divider(color: SamvadColors.border, height: 32),
                        _buildInfoTile('Bio', _user?.bio ?? 'Available on Samvad Messenger', Icons.info_outline),
                      ],
                    ),
                  ).animate().fadeIn(delay: 200.ms),

                  const SizedBox(height: 32),

                  // Action Buttons
                  _buildActionButton(
                    'Logout',
                    Icons.logout_rounded,
                    _logout,
                    isDestructive: true,
                  ),
                ],
              ),
            ),
    );
  }

  Widget _buildInfoTile(String label, String value, IconData icon) {
    return Row(
      children: [
        Icon(icon, color: SamvadColors.primaryLight, size: 24),
        const SizedBox(width: 20),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: const TextStyle(color: SamvadColors.onSurfaceMuted, fontSize: 12)),
              const SizedBox(height: 4),
              Text(value, style: const TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildActionButton(String label, IconData icon, VoidCallback onTap, {bool isDestructive = false}) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        decoration: BoxDecoration(
          color: isDestructive ? SamvadColors.error.withValues(alpha: 0.1) : SamvadColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: isDestructive ? SamvadColors.error.withValues(alpha: 0.3) : SamvadColors.border),
        ),
        child: Row(
          children: [
            Icon(icon, color: isDestructive ? SamvadColors.error : Colors.white70),
            const SizedBox(width: 16),
            Text(
              label,
              style: TextStyle(
                color: isDestructive ? SamvadColors.error : Colors.white,
                fontWeight: FontWeight.w600,
              ),
            ),
            const Spacer(),
            Icon(Icons.chevron_right, color: isDestructive ? SamvadColors.error.withValues(alpha: 0.5) : Colors.white24),
          ],
        ),
      ),
    );
  }
}
