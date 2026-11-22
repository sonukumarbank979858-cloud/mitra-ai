# मित्र AI (Mitra AI)

हिंदी वॉइस AI असिस्टेंट — Android (Kotlin + Jetpack Compose), Google Gemini पर आधारित। निजी इस्तेमाल के लिए साइडलोड APK।

- **Live वॉइस:** Gemini Live API (WebSocket `BidiGenerateContent`), 16 kHz माइक → 24 kHz आवाज़, बीच में टोकना (barge-in), function calling।
- **फ़ॉलबैक:** Android SpeechRecognizer (hi-IN) → `gemini-flash-latest` + tools → TextToSpeech (hi-IN)।
- **टूल्स:** ऐप खोलना, कॉल, SMS, WhatsApp (Accessibility से Send), नोटिफ़िकेशन पढ़ना/जवाब, अलार्म/टाइमर/रिमाइंडर, टॉर्च, वॉल्यूम, सेटिंग्स पैनल, फ़ोटो, कैमरे से देखकर बताना, YouTube, वेब खोज (Google Search grounding), स्क्रीन पढ़ना/टैप/स्क्रॉल, याददाश्त।
- पेमेंट/UPI/बैंकिंग ऐप्स ब्लॉक हैं। कॉल/मैसेज से पहले आवाज़ से पुष्टि।

## Build
हर push पर GitHub Actions signed release APK बनाता है और `v<run_number>` रिलीज़ बनाता है (versionName `1.0.<run>`, versionCode `<run>`)।

Repo secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD` (signing), वैकल्पिक `GEMINI_API_KEY` (बिल्ट-इन key; न हो तो ऐप पहली बार key माँगता है)।
दोबारा बिल्ड: Actions → "Build Mitra AI APK" → Run workflow।
