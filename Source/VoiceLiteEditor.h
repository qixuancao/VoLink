// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "SonobusPluginProcessor.h"
#include "juce_core/native/juce_BasicNativeHeaders.h"
#include "juce_core/native/juce_JNIHelpers_android.h"

bool voiceLiteSetMicrophoneCapture (bool enabled);

namespace juce {
#define JNI_CLASS_MEMBERS(METHOD, STATICMETHOD, FIELD, STATICFIELD, CALLBACK) \
    METHOD(startVoiceTunnel, "startVoiceTunnel", "()V") \
    METHOD(stopVoiceTunnel, "stopVoiceTunnel", "()V") \
    METHOD(showVoiceSettings, "showVoiceSettings", "()V") \
    METHOD(getVoiceState, "getVoiceState", "()I") \
    METHOD(getVoiceMessage, "getVoiceMessage", "()Ljava/lang/String;") \
    METHOD(getVoiceEchoStatus, "getVoiceEchoStatus", "()Ljava/lang/String;") \
    METHOD(getMicrophoneRequested, "getMicrophoneRequested", "()Z") \
    METHOD(setMicrophoneRequested, "setMicrophoneRequested", "(Z)V") \
    METHOD(onMicrophoneCaptureChanged, "onMicrophoneCaptureChanged", "(ZZ)V") \
    METHOD(onVoiceDiagnostics, "onVoiceDiagnostics", "(IZZFFIJJ)V") \
    METHOD(getVoiceDiagnostics, "getVoiceDiagnostics", "()Ljava/lang/String;")
DECLARE_JNI_CLASS(VoiceLiteActivity, JUCE_ANDROID_ACTIVITY_OVERRIDE_CLASSPATH)
#undef JNI_CLASS_MEMBERS
}

class VoiceLiteEditor final : public juce::AudioProcessorEditor, private juce::Timer {
public:
    explicit VoiceLiteEditor(SonobusAudioProcessor& p) : AudioProcessorEditor(p), processor(p) {
        title.setText(juce::String::fromUTF8("VoLink"), juce::dontSendNotification);
        title.setFont(juce::Font(32.0f, juce::Font::bold));
        subtitle.setText(juce::String::fromUTF8("Android ↔ Linux"), juce::dontSendNotification);
        subtitle.setFont(juce::Font(18.0f));
        status.setFont(juce::Font(18.0f));
        echo.setFont(juce::Font(14.0f));
        hint.setText(juce::String::fromUTF8("首次在设置中填写 SSH 密码\n连接后，在 Codex CLI 中开启 /voice"), juce::dontSendNotification);
        hint.setFont(juce::Font(15.0f));
        credits.setText(juce::String::fromUTF8("基于 SonoBus · GPLv3\nSSH 加密连接 · 密码仅保存在本机"), juce::dontSendNotification);
        credits.setFont(juce::Font(12.0f));
        for (auto* label : {&title, &subtitle, &status, &echo, &hint, &credits}) {
            label->setJustificationType(juce::Justification::centred);
            label->setColour(juce::Label::textColourId, juce::Colour(0xff263746));
            addAndMakeVisible(label);
        }
        title.setColour(juce::Label::textColourId, juce::Colour(0xff142c34));
        subtitle.setColour(juce::Label::textColourId, juce::Colour(0xff526b72));
        hint.setColour(juce::Label::textColourId, juce::Colour(0xff526b72));
        credits.setColour(juce::Label::textColourId, juce::Colour(0xff526b72));
        connect.setColour(juce::TextButton::buttonColourId, juce::Colour(0xff176b5d));
        connect.setColour(juce::TextButton::textColourOffId, juce::Colours::white);
        for (auto* button : {&mic, &settings}) {
            button->setColour(juce::TextButton::buttonColourId, juce::Colour(0xffdcece8));
            button->setColour(juce::TextButton::textColourOffId, juce::Colour(0xff153c36));
        }
        pushToTalk.setColour(juce::ToggleButton::textColourId, juce::Colour(0xff263746));
        pushToTalk.setColour(juce::ToggleButton::tickColourId, juce::Colour(0xff176b5d));
        settings.setButtonText(juce::String::fromUTF8("连接设置"));
        pushToTalk.setButtonText(juce::String::fromUTF8("按住说话（扬声器有回声时开启）"));
        for (auto* button : {&connect, &mic, &settings}) addAndMakeVisible(button);
        addAndMakeVisible(pushToTalk);
        connect.onClick = [this] {
            auto activity = juce::getMainActivity();
            if (activity == nullptr) return;
            if (lastState == 1 || lastState == 2) {
                setParameter(SonobusAudioProcessor::paramMainSendMute, 1);
                processor.removeAllRemotePeers();
                peerStarted = false;
                juce::getEnv()->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.stopVoiceTunnel);
            } else {
                juce::getEnv()->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.startVoiceTunnel);
            }
        };
        settings.onClick = [] {
            auto activity = juce::getMainActivity();
            if (activity != nullptr) juce::getEnv()->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.showVoiceSettings);
        };
        mic.onClick = [this] {
            if (!pushToTalk.getToggleState()) {
                auto activity = juce::getMainActivity();
                if (activity != nullptr)
                    juce::getEnv()->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.setMicrophoneRequested, (jboolean) muted);
            }
        };
        mic.onStateChange = [this] { if (pushToTalk.getToggleState()) updateMute(); };
        pushToTalk.onClick = [this] { updateMute(); };
        processor.removeAllRemotePeers();
        setParameter(SonobusAudioProcessor::paramAutoReconnectLast, 0);
        setParameter(SonobusAudioProcessor::paramDry, 0); // Never monitor the local mic in the speaker.
        setParameter(SonobusAudioProcessor::paramWet, 1);
        setParameter(SonobusAudioProcessor::paramSendChannels, 1);
        setParameter(SonobusAudioProcessor::paramDefaultNetbufMs, 80);
        processor.setDefaultAudioCodecFormat(3); // Mono Opus 64 kbps.
        processor.setDefaultAutoresizeBufferMode(SonobusAudioProcessor::AutoNetBufferModeAutoIncreaseOnly);
        processor.setMainReverbEnabled(false);
        setParameter(SonobusAudioProcessor::paramMetEnabled, 0);
        setParameter(SonobusAudioProcessor::paramMainSendMute, 1);
        setParameter(SonobusAudioProcessor::paramMainRecvMute, 0);
        setSize(440, 700);
        startTimerHz(4);
        timerCallback();
    }
    ~VoiceLiteEditor() override { stopTimer(); }
    void paint(juce::Graphics& g) override { g.fillAll(juce::Colour(0xfff4f8f6)); }
    void resized() override {
        auto area = getLocalBounds().reduced(24);
        int width = juce::jmin(650, area.getWidth());
        area = area.withSizeKeepingCentre(width, area.getHeight());
        const int gap = juce::jlimit(4, 24, (area.getHeight() - 550) / 8);
        title.setBounds(area.removeFromTop(52));
        subtitle.setBounds(area.removeFromTop(32));
        area.removeFromTop(gap);
        status.setBounds(area.removeFromTop(60));
        area.removeFromTop(gap);
        connect.setBounds(area.removeFromTop(72));
        area.removeFromTop(gap);
        mic.setBounds(area.removeFromTop(64));
        area.removeFromTop(gap / 2);
        pushToTalk.setBounds(area.removeFromTop(40));
        echo.setBounds(area.removeFromTop(34));
        area.removeFromTop(gap);
        settings.setBounds(area.removeFromTop(46));
        area.removeFromTop(gap);
        hint.setBounds(area.removeFromTop(58));
        credits.setBounds(area.removeFromBottom(44));
    }
private:
    SonobusAudioProcessor& processor;
    juce::Label title, subtitle, status, echo, hint, credits;
    juce::TextButton connect, mic, settings;
    juce::ToggleButton pushToTalk;
    bool muted = true, peerStarted = false;
    int captureApplied = -1, lastCaptureAttempt = -1;
    int lastState = 0;
    void setParameter(const juce::String& id, float value) {
        if (auto* param = processor.getValueTreeState().getParameter(id))
            param->setValueNotifyingHost(param->convertTo0to1(value));
    }
    void updateMute() {
        bool ptt = pushToTalk.getToggleState();
        bool talking = lastState == 2 && !muted && (!ptt || mic.isDown());
        setParameter(SonobusAudioProcessor::paramMainSendMute, talking ? 0 : 1);
        // Half-duplex fallback eliminates acoustic feedback while the button is held.
        setParameter(SonobusAudioProcessor::paramMainRecvMute, ptt && talking ? 1 : 0);
        mic.setButtonText(juce::String::fromUTF8(ptt ? (talking ? "正在说话…" : "按住说话")
            : (muted ? "麦克风已静音 · 点击开启" : "麦克风开启 · 点击静音")));
    }
    void timerCallback() override {
        auto activity = juce::getMainActivity();
        if (activity == nullptr) return;
        auto* env = juce::getEnv();
        int state = env->CallIntMethod(activity.get(), juce::VoiceLiteActivity.getVoiceState);
        bool requested = env->CallBooleanMethod(activity.get(), juce::VoiceLiteActivity.getMicrophoneRequested);
        bool capture = state == 2 && requested;
        if (lastCaptureAttempt != (int) capture) {
            lastCaptureAttempt = (int) capture;
            // Mute before changing the audio device, including failure paths.
            setParameter(SonobusAudioProcessor::paramMainSendMute, 1);
            bool success = voiceLiteSetMicrophoneCapture(capture);
            captureApplied = success ? (int) capture : -1;
            env->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.onMicrophoneCaptureChanged,
                                (jboolean) capture, (jboolean) success);
        }
        muted = !capture || captureApplied != 1;
        if (state != 2 && peerStarted) { processor.removeAllRemotePeers(); peerStarted = false; }
        lastState = state;
        if (state == 2 && !peerStarted) {
            processor.connectRemotePeer("127.0.0.1", 39021, "Android", "", true);
            peerStarted = true;
        }
        juce::LocalRef<jstring> text((jstring)env->CallObjectMethod(activity.get(), juce::VoiceLiteActivity.getVoiceMessage));
        juce::String statusText = juce::juceString(text);
        if (state == 2 && processor.getNumberRemotePeers() > 0 && processor.getRemotePeerBytesReceived(0) > 0)
            statusText = juce::String::fromUTF8("已连接 · 双向音频通道就绪");
        status.setText(statusText, juce::dontSendNotification);
        juce::LocalRef<jstring> effectText((jstring)env->CallObjectMethod(activity.get(), juce::VoiceLiteActivity.getVoiceEchoStatus));
        echo.setText(juce::juceString(effectText), juce::dontSendNotification);
        connect.setButtonText(juce::String::fromUTF8(state == 1 ? "正在连接 · 点击取消" : state == 2 ? "断开连接" : "连接电脑"));
        mic.setEnabled(state == 2);
        updateMute();
        auto& meter = processor.getInputMeterSource();
        float peak = 0, rms = 0;
        for (int channel = 0; channel < meter.getNumChannels(); ++channel) {
            peak = juce::jmax(peak, meter.getMaxLevel(channel));
            rms = juce::jmax(rms, meter.getRMSLevel(channel));
        }
        int peers = processor.getNumberRemotePeers();
        int64_t tx = 0, rx = 0;
        for (int i = 0; i < peers; ++i) {
            tx += processor.getRemotePeerBytesSent(i);
            rx += processor.getRemotePeerBytesReceived(i);
        }
        env->CallVoidMethod(activity.get(), juce::VoiceLiteActivity.onVoiceDiagnostics,
            (jint) captureApplied, (jboolean) (muted || (pushToTalk.getToggleState() && !mic.isDown())),
            (jboolean) pushToTalk.getToggleState(), (jfloat) peak, (jfloat) rms, (jint) peers, (jlong) tx, (jlong) rx);
        juce::LocalRef<jstring> diagnostics((jstring)env->CallObjectMethod(activity.get(), juce::VoiceLiteActivity.getVoiceDiagnostics));
        hint.setText(juce::juceString(diagnostics), juce::dontSendNotification);
    }
};
