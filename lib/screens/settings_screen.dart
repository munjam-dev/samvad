import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import '../samvad_app.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _darkMode = true;
  bool _notifications = true;
  bool _autoDownload = true;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: SamvadColors.background,
      appBar: AppBar(
        title: const Text('Settings'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader('Preference'),
            _buildSettingsGroup([
              _buildToggleTile(
                'Dark Mode',
                'OLED optimized theme',
                Icons.dark_mode_outlined,
                _darkMode,
                (v) => setState(() => _darkMode = v),
              ),
              _buildDivider(),
              _buildToggleTile(
                'Notifications',
                'Real-time alerts',
                Icons.notifications_none_rounded,
                _notifications,
                (v) => setState(() => _notifications = v),
              ),
            ]),
            
            const SizedBox(height: 24),
            _buildSectionHeader('Privacy'),
            _buildSettingsGroup([
              _buildActionTile(
                'Blocked Contacts',
                'Manage blocked users',
                Icons.block_flipped,
                () {},
              ),
              _buildDivider(),
              _buildActionTile(
                'Last Seen',
                'Privacy visibility',
                Icons.visibility_outlined,
                () {},
              ),
            ]),

            const SizedBox(height: 24),
            _buildSectionHeader('Storage & Data'),
            _buildSettingsGroup([
              _buildToggleTile(
                'Auto-Download',
                'Download media on Wi-Fi',
                Icons.download_for_offline_outlined,
                _autoDownload,
                (v) => setState(() => _autoDownload = v),
              ),
              _buildDivider(),
              _buildActionTile(
                'Storage Usage',
                'Clear cache and data',
                Icons.storage_rounded,
                () {},
              ),
            ]),

            const SizedBox(height: 24),
            _buildSectionHeader('About'),
            _buildSettingsGroup([
              _buildActionTile(
                'Samvad Version',
                '1.0.0 Stable Release',
                Icons.info_outline_rounded,
                null,
                showArrow: false,
              ),
              _buildDivider(),
              _buildActionTile(
                'Privacy Policy',
                'Terms and conditions',
                Icons.description_outlined,
                () {},
              ),
            ]),
            
            const SizedBox(height: 40),
            Center(
              child: Text(
                'Samvad Messenger Premium',
                style: TextStyle(
                  color: SamvadColors.onSurfaceMuted.withOpacity(0.5),
                  fontSize: 12,
                  letterSpacing: 1.2,
                ),
              ),
            ),
            const SizedBox(height: 20),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.only(left: 8, bottom: 12),
      child: Text(
        title.toUpperCase(),
        style: const TextStyle(
          color: SamvadColors.primaryLight,
          fontSize: 12,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.5,
        ),
      ),
    ).animate().fadeIn(duration: 400.ms);
  }

  Widget _buildSettingsGroup(List<Widget> children) {
    return Container(
      decoration: BoxDecoration(
        color: SamvadColors.surface,
        borderRadius: BorderRadius.circular(24),
        border: Border.all(color: SamvadColors.border),
      ),
      child: Column(children: children),
    ).animate().fadeIn(duration: 500.ms).slideY(begin: 0.05, end: 0);
  }

  Widget _buildToggleTile(String title, String subtitle, IconData icon, bool value, Function(bool) onChanged) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
      leading: Icon(icon, color: Colors.white70),
      title: Text(title, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
      subtitle: Text(subtitle, style: const TextStyle(color: SamvadColors.onSurfaceMuted, fontSize: 13)),
      trailing: Switch(
        value: value,
        onChanged: onChanged,
        activeColor: SamvadColors.primary,
        activeTrackColor: SamvadColors.primary.withOpacity(0.3),
      ),
    );
  }

  Widget _buildActionTile(String title, String subtitle, IconData icon, VoidCallback? onTap, {bool showArrow = true}) {
    return ListTile(
      onTap: onTap,
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
      leading: Icon(icon, color: Colors.white70),
      title: Text(title, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600)),
      subtitle: Text(subtitle, style: const TextStyle(color: SamvadColors.onSurfaceMuted, fontSize: 13)),
      trailing: showArrow ? const Icon(Icons.chevron_right, color: Colors.white24) : null,
    );
  }

  Widget _buildDivider() {
    return const Padding(
      padding: EdgeInsets.symmetric(horizontal: 20),
      child: Divider(color: SamvadColors.border, height: 1),
    );
  }
}
