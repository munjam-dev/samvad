import 'package:flutter_test/flutter_test.dart';
import 'package:samvad_messenger/samvad_app.dart';

void main() {
  testWidgets('Splash screen smoke test', (WidgetTester tester) async {
    // Build our app and trigger a frame.
    await tester.pumpWidget(const SamvadApp());

    // Verify that the splash screen shows 'Samvad'.
    expect(find.text('Samvad'), findsOneWidget);
  });
}
