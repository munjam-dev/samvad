import 'package:flutter/material.dart';
import 'package:flutter_animate/flutter_animate.dart';
import 'package:country_picker/country_picker.dart';
import '../samvad_app.dart';
import 'dart:async';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> with SingleTickerProviderStateMixin {
  late TabController _tabController;

  // Phone Form Controllers
  final _phoneController = TextEditingController();
  final _otpController = TextEditingController();
  final _phoneNameController = TextEditingController();
  
  // Email Form Controllers
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _emailNameController = TextEditingController();

  bool _isSignUp = false;
  bool _otpSent = false;
  bool _isLoading = false;
  bool _termsAccepted = true;
  int _resendSeconds = 0;
  Timer? _resendTimer;
  
  String _selectedCountryCode = '+91';

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
  }

  @override
  void dispose() {
    _tabController.dispose();
    _phoneController.dispose();
    _otpController.dispose();
    _phoneNameController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _emailNameController.dispose();
    _resendTimer?.cancel();
    super.dispose();
  }

  void _startResendTimer() {
    _resendTimer?.cancel();
    setState(() => _resendSeconds = 30);
    _resendTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      if (_resendSeconds > 0) {
        setState(() => _resendSeconds--);
      } else {
        timer.cancel();
      }
    });
  }

  // --- Phone OTP Flow ---
  Future<void> _sendOTP() async {
    if (_phoneController.text.trim().isEmpty || _phoneController.text.trim().length < 10) {
      _showError('Please enter a valid phone number');
      return;
    }

    setState(() => _isLoading = true);

    try {
      final result = await ApiService.sendOTP(
        phone: _phoneController.text.trim(),
        countryCode: _selectedCountryCode,
      );

      setState(() => _isLoading = false);
      if (result['success']) {
        setState(() => _otpSent = true);
        _startResendTimer();
        _showSuccess(result['message'] ?? 'OTP sent successfully');
      } else {
        _showError(result['message'] ?? 'Failed to send OTP');
      }
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('Connection error. Please try again.');
    }
  }

  Future<void> _verifyOTP() async {
    if (_otpController.text.trim().isEmpty || _otpController.text.trim().length != 6) {
      _showError('Please enter the 6-digit OTP code');
      return;
    }

    if (!_termsAccepted) {
      _showError('Please accept the Terms and Privacy Policy');
      return;
    }

    setState(() => _isLoading = true);

    try {
      final result = await ApiService.verifyOTP(
        phone: _phoneController.text.trim(),
        countryCode: _selectedCountryCode,
        otp: _otpController.text.trim(),
        name: _phoneNameController.text.trim().isNotEmpty ? _phoneNameController.text.trim() : null,
      );

      setState(() => _isLoading = false);
      if (result['success']) {
        if (mounted) Navigator.pushReplacementNamed(context, '/home');
      } else {
        _showError(result['message'] ?? 'Invalid OTP code');
      }
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('Verification failed. Try again.');
    }
  }

  // --- Email Auth Flow ---
  Future<void> _handleEmailAuth() async {
    final email = _emailController.text.trim();
    final password = _passwordController.text.trim();

    if (email.isEmpty || !email.contains('@')) {
      _showError('Please enter a valid email address');
      return;
    }

    if (password.isEmpty || password.length < 6) {
      _showError('Password must be at least 6 characters');
      return;
    }

    if (!_termsAccepted) {
      _showError('Please accept the Terms and Privacy Policy');
      return;
    }

    setState(() => _isLoading = true);

    try {
      Map<String, dynamic> result;
      if (_isSignUp) {
        final name = _emailNameController.text.trim();
        if (name.isEmpty) {
          _showError('Please enter your display name');
          setState(() => _isLoading = false);
          return;
        }
        result = await ApiService.signUpWithEmail(
          email: email,
          password: password,
          displayName: name,
        );
      } else {
        result = await ApiService.signInWithEmail(
          email: email,
          password: password,
        );
      }

      setState(() => _isLoading = false);
      if (result['success']) {
        if (mounted) Navigator.pushReplacementNamed(context, '/home');
      } else {
        _showError(result['message'] ?? 'Authentication failed');
      }
    } catch (e) {
      setState(() => _isLoading = false);
      _showError('Authentication error. Try again.');
    }
  }

  Future<void> _handleForgotPassword() async {
    final email = _emailController.text.trim();
    if (email.isEmpty || !email.contains('@')) {
      _showError('Enter your email address to reset password');
      return;
    }

    setState(() => _isLoading = true);
    final result = await ApiService.resetPassword(email);
    setState(() => _isLoading = false);

    if (result['success']) {
      _showSuccess(result['message'] ?? 'Password reset email sent');
    } else {
      _showError(result['message'] ?? 'Reset password failed');
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: SamvadColors.error,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }

  void _showSuccess(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: SamvadColors.success,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Container(
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: SamvadColors.gradientBackground,
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
            child: Column(
              children: [
                const SamvadLogo(size: 72).animate().fadeIn().scale(),
                const SizedBox(height: 20),
                const Text(
                  'Welcome to Samvad',
                  style: TextStyle(
                    fontSize: 28, 
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ).animate().fadeIn(delay: 150.ms),
                const SizedBox(height: 6),
                Text(
                  'Meaningful conversations, securely connected',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.6)),
                ).animate().fadeIn(delay: 200.ms),
                const SizedBox(height: 32),

                // Tab Switcher
                Container(
                  height: 48,
                  decoration: BoxDecoration(
                    color: SamvadColors.surface.withValues(alpha: 0.8),
                    borderRadius: BorderRadius.circular(24),
                    border: Border.all(color: SamvadColors.border),
                  ),
                  child: TabBar(
                    controller: _tabController,
                    indicator: BoxDecoration(
                      color: SamvadColors.primary,
                      borderRadius: BorderRadius.circular(20),
                    ),
                    indicatorSize: TabBarIndicatorSize.tab,
                    labelColor: Colors.white,
                    unselectedLabelColor: SamvadColors.onSurfaceMuted,
                    dividerColor: Colors.transparent,
                    tabs: const [
                      Tab(text: 'Phone OTP'),
                      Tab(text: 'Email'),
                    ],
                  ),
                ),

                const SizedBox(height: 24),

                // Form Container
                Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: SamvadColors.surface.withValues(alpha: 0.5),
                    borderRadius: BorderRadius.circular(28),
                    border: Border.all(color: SamvadColors.border.withValues(alpha: 0.5)),
                  ),
                  child: SizedBox(
                    height: 380,
                    child: TabBarView(
                      controller: _tabController,
                      children: [
                        // --- Phone OTP Tab ---
                        SingleChildScrollView(
                          child: Column(
                            children: [
                              if (!_otpSent) ...[
                                Row(
                                  children: [
                                    Expanded(
                                      flex: 3,
                                      child: TextButton(
                                        onPressed: () {
                                          showCountryPicker(
                                            context: context,
                                            showPhoneCode: true,
                                            onSelect: (Country country) {
                                              setState(() {
                                                _selectedCountryCode = '+${country.phoneCode}';
                                              });
                                            },
                                            countryListTheme: CountryListThemeData(
                                              borderRadius: BorderRadius.circular(20),
                                              backgroundColor: SamvadColors.surface,
                                              textStyle: const TextStyle(color: Colors.white),
                                              searchTextStyle: const TextStyle(color: Colors.white),
                                              bottomSheetHeight: MediaQuery.of(context).size.height * 0.7,
                                            ),
                                          );
                                        },
                                        child: Text(
                                          _selectedCountryCode,
                                          style: const TextStyle(
                                            color: Colors.white,
                                            fontSize: 16,
                                            fontWeight: FontWeight.bold,
                                          ),
                                        ),
                                      ),
                                    ),
                                    const SizedBox(width: 8),
                                    Expanded(
                                      flex: 6,
                                      child: TextField(
                                        controller: _phoneController,
                                        keyboardType: TextInputType.phone,
                                        maxLength: 10,
                                        style: const TextStyle(color: Colors.white),
                                        decoration: const InputDecoration(
                                          hintText: 'Phone Number',
                                          counterText: '',
                                        ),
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 16),
                                TextField(
                                  controller: _phoneNameController,
                                  style: const TextStyle(color: Colors.white),
                                  decoration: const InputDecoration(
                                    hintText: 'Display Name (Optional)',
                                    prefixIcon: Icon(Icons.person_outline, color: SamvadColors.onSurfaceMuted),
                                  ),
                                ),
                              ] else ...[
                                TextField(
                                  controller: _otpController,
                                  keyboardType: TextInputType.number,
                                  maxLength: 6,
                                  textAlign: TextAlign.center,
                                  style: const TextStyle(
                                    fontSize: 28, 
                                    letterSpacing: 12, 
                                    fontWeight: FontWeight.bold,
                                    color: Colors.white,
                                  ),
                                  decoration: const InputDecoration(
                                    hintText: '000000',
                                    hintStyle: TextStyle(letterSpacing: 12, color: SamvadColors.onSurfaceMuted),
                                    counterText: '',
                                  ),
                                ),
                                const SizedBox(height: 16),
                                Text(
                                  _resendSeconds > 0 
                                      ? 'Resend code in $_resendSeconds s'
                                      : 'Didn\'t receive code?',
                                  style: TextStyle(color: Colors.white.withValues(alpha: 0.6)),
                                ),
                                if (_resendSeconds == 0)
                                  TextButton(
                                    onPressed: _sendOTP,
                                    child: const Text(
                                      'Resend Code', 
                                      style: TextStyle(fontWeight: FontWeight.bold, color: SamvadColors.primaryLight),
                                    ),
                                  ),
                              ],
                              const SizedBox(height: 20),
                              Row(
                                children: [
                                  Checkbox(
                                    value: _termsAccepted,
                                    onChanged: (v) => setState(() => _termsAccepted = v!),
                                    activeColor: SamvadColors.primary,
                                  ),
                                  const Expanded(
                                    child: Text(
                                      'I agree to Terms & Privacy Policy', 
                                      style: TextStyle(fontSize: 12, color: Colors.white70),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 16),
                              SizedBox(
                                width: double.infinity,
                                child: ElevatedButton(
                                  onPressed: _isLoading ? null : (_otpSent ? _verifyOTP : _sendOTP),
                                  style: ElevatedButton.styleFrom(
                                    padding: const EdgeInsets.symmetric(vertical: 14),
                                  ),
                                  child: _isLoading 
                                      ? const SizedBox(
                                          height: 20, 
                                          width: 20, 
                                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                        )
                                      : Text(_otpSent ? 'Verify OTP' : 'Send OTP'),
                                ),
                              ),
                              if (_otpSent) ...[
                                const SizedBox(height: 8),
                                TextButton(
                                  onPressed: () => setState(() => _otpSent = false),
                                  child: const Text(
                                    'Change Phone Number',
                                    style: TextStyle(color: Colors.white70),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),

                        // --- Email Auth Tab ---
                        SingleChildScrollView(
                          child: Column(
                            children: [
                              if (_isSignUp) ...[
                                TextField(
                                  controller: _emailNameController,
                                  style: const TextStyle(color: Colors.white),
                                  decoration: const InputDecoration(
                                    hintText: 'Display Name',
                                    prefixIcon: Icon(Icons.person_outline, color: SamvadColors.onSurfaceMuted),
                                  ),
                                ),
                                const SizedBox(height: 12),
                              ],
                              TextField(
                                controller: _emailController,
                                keyboardType: TextInputType.emailAddress,
                                style: const TextStyle(color: Colors.white),
                                decoration: const InputDecoration(
                                  hintText: 'Email Address',
                                  prefixIcon: Icon(Icons.email_outlined, color: SamvadColors.onSurfaceMuted),
                                ),
                              ),
                              const SizedBox(height: 12),
                              TextField(
                                controller: _passwordController,
                                obscureText: true,
                                style: const TextStyle(color: Colors.white),
                                decoration: const InputDecoration(
                                  hintText: 'Password',
                                  prefixIcon: Icon(Icons.lock_outline, color: SamvadColors.onSurfaceMuted),
                                ),
                              ),
                              if (!_isSignUp) ...[
                                Align(
                                  alignment: Alignment.centerRight,
                                  child: TextButton(
                                    onPressed: _handleForgotPassword,
                                    child: const Text(
                                      'Forgot Password?',
                                      style: TextStyle(color: SamvadColors.primaryLight, fontSize: 12),
                                    ),
                                  ),
                                ),
                              ] else ...[
                                const SizedBox(height: 12),
                              ],
                              Row(
                                children: [
                                  Checkbox(
                                    value: _termsAccepted,
                                    onChanged: (v) => setState(() => _termsAccepted = v!),
                                    activeColor: SamvadColors.primary,
                                  ),
                                  const Expanded(
                                    child: Text(
                                      'I agree to Terms & Privacy Policy', 
                                      style: TextStyle(fontSize: 12, color: Colors.white70),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 12),
                              SizedBox(
                                width: double.infinity,
                                child: ElevatedButton(
                                  onPressed: _isLoading ? null : _handleEmailAuth,
                                  style: ElevatedButton.styleFrom(
                                    padding: const EdgeInsets.symmetric(vertical: 14),
                                  ),
                                  child: _isLoading 
                                      ? const SizedBox(
                                          height: 20, 
                                          width: 20, 
                                          child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                        )
                                      : Text(_isSignUp ? 'Create Account' : 'Sign In'),
                                ),
                              ),
                              const SizedBox(height: 8),
                              TextButton(
                                onPressed: () => setState(() => _isSignUp = !_isSignUp),
                                child: Text(
                                  _isSignUp 
                                      ? 'Already have an account? Sign In'
                                      : 'Don\'t have an account? Sign Up',
                                  style: const TextStyle(color: Colors.white70, fontSize: 13),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                ).animate().fadeIn(delay: 300.ms).slideY(begin: 0.1, end: 0),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
