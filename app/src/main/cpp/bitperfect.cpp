// Exclusive audio output: a USB Audio Class DAC driven directly over usbdevfs isochronous
// transfers, or an AAudio exclusive stream on the device's own output. Both read from one
// ring buffer that the player fills; nothing here runs unless a song is playing in one of
// these modes.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/api-level.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <errno.h>
#include <unistd.h>
#include <memory>
#include <algorithm>
#include <string.h>
#include <math.h>
#include <atomic>
#include <thread>
#include <vector>
#include <mutex>

#define TAG "BitPerfect"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Single producer (player thread) / single consumer (USB thread or AAudio callback).
class Ring {
public:
    explicit Ring(size_t bytes) : buf_(bytes), cap_(bytes) {}
    // Read position, taking a pending clear into account (both sides may call this).
    size_t base() const { return std::max(r_.load(std::memory_order_acquire), clearTo_.load(std::memory_order_acquire)); }
    size_t readable() const { return w_.load(std::memory_order_acquire) - base(); }
    size_t writable() const { return cap_ - readable(); }
    void write(const uint8_t* src, size_t n) {
        size_t w = w_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < n;) {
            size_t at = (w + i) % cap_;
            size_t chunk = std::min(n - i, cap_ - at);
            memcpy(&buf_[at], src + i, chunk);
            i += chunk;
        }
        w_.store(w + n, std::memory_order_release);
    }
    size_t read(uint8_t* dst, size_t n) {
        size_t r = base();
        n = std::min(n, w_.load(std::memory_order_acquire) - r);
        for (size_t i = 0; i < n;) {
            size_t at = (r + i) % cap_;
            size_t chunk = std::min(n - i, cap_ - at);
            memcpy(dst + i, &buf_[at], chunk);
            i += chunk;
        }
        r_.store(r + n, std::memory_order_release);
        return n;
    }
    // Producer side: everything written so far is dropped; the consumer skips to it on its next read.
    void clear() { clearTo_.store(w_.load(std::memory_order_relaxed), std::memory_order_release); }
private:
    std::vector<uint8_t> buf_;
    size_t cap_;
    std::atomic<size_t> r_{0}, w_{0}, clearTo_{0};
};

struct Stream {
    // Input from the player: 16-bit or float, interleaved.
    bool inFloat = false;
    int channels = 2;
    int rate = 44100;
    // Output sample layout (USB subslot / AAudio sample size) and valid bits.
    int outBytes = 2;
    int outBits = 16;
    bool outFloat = false;
    std::atomic<float> gain{1.0f};
    std::atomic<bool> exact{true};  // false once any sample left changed (volume, depth, lossy float)
    std::unique_ptr<Ring> ring;
    std::vector<uint8_t> scratch;  // producer-only conversion buffer, reused
    std::atomic<int64_t> framesPlayed{0};
    std::atomic<uint32_t> generation{0};  // bumped by a flush; audio queued before it isn't counted
    std::atomic<bool> running{false};

    // USB
    int fd = -1;
    int ep = 0;
    int maxPacket = 0;
    int packetsPerSecond = 1000;
    int fbEp = 0;
    int busIntervalsPerPacket = 1;  // feedback value is per bus (micro)frame
    int packetsPerUrb = 8;
    double fbFrames = 0;            // frames per packet from feedback, 0 = use nominal
    std::thread thread;

    // AAudio
    AAudioStream* aaudio = nullptr;

    int frameBytes() const { return channels * outBytes; }
};

inline int32_t toS32(const uint8_t* in, bool isFloat, float gain, bool* exact) {
    double v;
    if (isFloat) {
        float f;
        memcpy(&f, in, 4);
        v = (double) f * gain;
        if (v > 1.0) v = 1.0;
        if (v < -1.0) v = -1.0;
        // 24-bit sources decode to floats that are exact at 2^23 steps; keep them exact.
        int64_t s24 = llround(v * 8388608.0);
        if (s24 > 8388607) s24 = 8388607;
        if (s24 < -8388608) s24 = -8388608;
        // A float that isn't exactly a 24-bit step came from a lossy decoder or from processing.
        if ((float) ((double) s24 / 8388608.0) != f) *exact = false;
        return (int32_t) (s24 * 256);
    }
    int16_t s;
    memcpy(&s, in, 2);
    if (gain >= 1.0f) return (int32_t) s * 65536;
    return (int32_t) llround((double) s * gain * 65536.0);
}

// Converts input frames into the output layout and pushes them into the ring.
size_t pushFrames(Stream* s, const uint8_t* in, size_t inBytes) {
    const int inSample = s->inFloat ? 4 : 2;
    const size_t inFrame = (size_t) inSample * s->channels;
    const size_t outFrame = (size_t) s->frameBytes();
    size_t frames = std::min(inBytes / inFrame, s->ring->writable() / outFrame);
    if (frames == 0) return 0;
    const float gain = s->gain.load();
    bool exact = gain >= 1.0f;
    if (s->scratch.size() < frames * outFrame) s->scratch.resize(frames * outFrame);
    uint8_t* o = s->scratch.data();
    const uint8_t* p = in;
    const size_t samples = frames * s->channels;
    for (size_t i = 0; i < samples; i++, p += inSample) {
        if (s->outFloat) {
            float f;
            if (s->inFloat) { memcpy(&f, p, 4); f *= gain; }
            else { int16_t v; memcpy(&v, p, 2); f = (float) v / 32768.0f * gain; }
            memcpy(o, &f, 4);
            o += 4;
            continue;
        }
        if (s->outBytes == 2 && !s->inFloat && gain >= 1.0f) {  // the common 16-bit case, untouched
            memcpy(o, p, 2);
            o += 2;
            continue;
        }
        int32_t v = toS32(p, s->inFloat, gain, &exact);
        if (s->outBits < 32) {
            int32_t kept = v & (int32_t) (0xFFFFFFFFu << (32 - s->outBits));
            if (kept != v) exact = false;  // the output is narrower than the source
            v = kept;
        }
        // Samples sit in the high bytes of the subslot, little-endian.
        for (int b = 0; b < s->outBytes; b++) *o++ = (uint8_t) ((uint32_t) v >> (8 * (4 - s->outBytes + b)));
    }
    if (!exact) s->exact.store(false);
    s->ring->write(s->scratch.data(), frames * outFrame);
    return frames * inFrame;
}

// ---------- USB ----------

// Each transfer carries about 4 ms of audio and about 32 ms stays queued at the DAC, so a
// late wake-up of this thread doesn't starve the DAC, and the thread wakes ~250 times a second.
constexpr int kUrbMs = 4;
constexpr int kQueuedMs = 32;
constexpr int kUrbs = kQueuedMs / kUrbMs;
constexpr int kMaxPacketsPerUrb = 32;

struct OutUrb {
    usbdevfs_urb* urb;
    std::vector<uint8_t> data;
    int frames = 0;  // frames of real audio (silence padding excluded)
    uint32_t generation = 0;
    bool inFlight = false;
};

usbdevfs_urb* newIsoUrb(int packets) {
    size_t size = sizeof(usbdevfs_urb) + packets * sizeof(usbdevfs_iso_packet_desc);
    auto* urb = (usbdevfs_urb*) calloc(1, size);
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = packets;
    return urb;
}

void fillOutUrb(Stream* s, OutUrb* u, double* carry) {
    const int fb = s->frameBytes();
    const double nominal = (double) s->rate / s->packetsPerSecond;
    const double perPacket = s->fbFrames > 0 ? s->fbFrames : nominal;
    size_t offset = 0;
    int frames = 0;
    for (int i = 0; i < s->packetsPerUrb; i++) {
        *carry += perPacket;
        int n = (int) *carry;
        *carry -= n;
        if (n * fb > s->maxPacket) n = s->maxPacket / fb;
        size_t bytes = (size_t) n * fb;
        size_t got = s->ring->read(&u->data[offset], bytes);
        if (got < bytes) memset(&u->data[offset + got], 0, bytes - got);  // underrun: silence
        u->urb->iso_frame_desc[i].length = (unsigned) bytes;
        offset += bytes;
        frames += (int) (got / fb);
    }
    u->urb->buffer = u->data.data();
    u->urb->buffer_length = (int) offset;
    u->frames = frames;
    u->generation = s->generation.load();
}

// A transfer the DAC finished: its audio counts as played unless a seek dropped it meanwhile.
void countPlayed(Stream* s, OutUrb* u) {
    u->inFlight = false;
    if (u->generation == s->generation.load()) s->framesPlayed.fetch_add(u->frames);
}

void usbLoop(Stream* s) {
    // Audio priority (ANDROID_PRIORITY_URGENT_AUDIO, then AUDIO); best effort, never fatal.
    if (setpriority(PRIO_PROCESS, 0, -19) != 0) setpriority(PRIO_PROCESS, 0, -16);
    s->packetsPerUrb = std::max(1, std::min(kMaxPacketsPerUrb, s->packetsPerSecond * kUrbMs / 1000));
    std::vector<OutUrb> outs(kUrbs);
    double carry = 0;
    for (auto& u : outs) {
        u.urb = newIsoUrb(s->packetsPerUrb);
        u.urb->endpoint = (unsigned char) s->ep;
        u.data.resize((size_t) s->maxPacket * s->packetsPerUrb);
        u.urb->usercontext = &u;
        fillOutUrb(s, &u, &carry);
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, u.urb) < 0) LOGW("submit failed: %s", strerror(errno));
        else u.inFlight = true;
    }
    usbdevfs_urb* fbUrb = nullptr;
    std::vector<uint8_t> fbData(8);
    if (s->fbEp) {
        fbUrb = newIsoUrb(1);
        fbUrb->endpoint = (unsigned char) s->fbEp;
        fbUrb->buffer = fbData.data();
        fbUrb->buffer_length = 4;
        fbUrb->iso_frame_desc[0].length = 4;
        fbUrb->usercontext = nullptr;
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, fbUrb) < 0) { free(fbUrb); fbUrb = nullptr; }
    }
    const double nominal = (double) s->rate / s->packetsPerSecond;
    while (s->running.load()) {
        usbdevfs_urb* done = nullptr;
        if (ioctl(s->fd, USBDEVFS_REAPURB, &done) < 0) {
            if (errno == EINTR) continue;
            LOGW("reap failed: %s", strerror(errno));
            break;
        }
        if (done == fbUrb) {
            int len = fbUrb->iso_frame_desc[0].actual_length;
            double value = 0;
            if (len == 3) value = (fbData[0] | fbData[1] << 8 | fbData[2] << 16) / 16384.0;   // 10.14
            else if (len >= 4) value = (fbData[0] | fbData[1] << 8 | fbData[2] << 16 | (uint32_t) fbData[3] << 24) / 65536.0;  // 16.16
            double perPacket = value * s->busIntervalsPerPacket;
            if (perPacket > nominal * 0.75 && perPacket < nominal * 1.25) s->fbFrames = perPacket;
            if (s->running.load()) ioctl(s->fd, USBDEVFS_SUBMITURB, fbUrb);
            continue;
        }
        auto* u = (OutUrb*) done->usercontext;
        if (!u) continue;
        countPlayed(s, u);
        if (!s->running.load()) break;
        fillOutUrb(s, u, &carry);
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, u->urb) < 0) {
            LOGW("resubmit failed: %s", strerror(errno));
            break;
        }
        u->inFlight = true;
    }
    // Stopping (pause): what is already on its way plays out (at most ~32 ms) and counts as
    // played, so a pause loses no audio. Only the feedback transfer is cancelled.
    if (fbUrb) ioctl(s->fd, USBDEVFS_DISCARDURB, fbUrb);
    bool fbPending = fbUrb != nullptr;
    auto anyInFlight = [&] { for (auto& u : outs) if (u.inFlight) return true; return false; };
    for (int tries = 0; (fbPending || anyInFlight()) && tries < 100;) {
        usbdevfs_urb* done = nullptr;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) >= 0) {
            if (done == fbUrb) fbPending = false;
            else if (auto* u = (OutUrb*) done->usercontext) countPlayed(s, u);
            continue;
        }
        if (errno != EAGAIN) break;
        usleep(1000);
        tries++;
    }
    // Anything still out (device gone or stuck): cancel it and collect it before freeing.
    for (auto& u : outs) if (u.inFlight) ioctl(s->fd, USBDEVFS_DISCARDURB, u.urb);
    for (int tries = 0; (fbPending || anyInFlight()) && tries < 100;) {
        usbdevfs_urb* done = nullptr;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) >= 0) {
            if (done == fbUrb) fbPending = false;
            else if (auto* u = (OutUrb*) done->usercontext) u->inFlight = false;
            continue;
        }
        if (errno != EAGAIN) break;
        usleep(1000);
        tries++;
    }
    for (auto& u : outs) free(u.urb);
    free(fbUrb);
    s->running.store(false);
}

void usbStart(Stream* s) {
    if (s->running.exchange(true)) return;
    if (s->thread.joinable()) s->thread.join();  // a loop that ended on its own (USB error)
    s->thread = std::thread(usbLoop, s);
}

void usbStop(Stream* s) {
    if (!s->running.exchange(false)) {
        if (s->thread.joinable()) s->thread.join();
        return;
    }
    if (s->thread.joinable()) s->thread.join();
}

// ---------- AAudio ----------

aaudio_data_callback_result_t aaudioCallback(AAudioStream*, void* user, void* audio, int32_t frames) {
    auto* s = (Stream*) user;
    size_t bytes = (size_t) frames * s->frameBytes();
    size_t got = s->ring->read((uint8_t*) audio, bytes);
    if (got < bytes) memset((uint8_t*) audio + got, 0, bytes - got);
    s->framesPlayed.fetch_add((int64_t) (got / s->frameBytes()));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

Stream* fromHandle(jlong h) { return reinterpret_cast<Stream*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_usbSpeed(JNIEnv*, jclass, jint fd) {
    return ioctl(fd, USBDEVFS_GET_SPEED, 0);
}

JNIEXPORT jboolean JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_reattachKernelDriver(JNIEnv*, jclass, jint fd, jint iface) {
    usbdevfs_ioctl command{};
    command.ifno = iface;
    command.ioctl_code = USBDEVFS_CONNECT;
    command.data = nullptr;
    return ioctl(fd, USBDEVFS_IOCTL, &command) >= 0;
}

JNIEXPORT jlong JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_openUsb(
        JNIEnv*, jclass, jint fd, jint ep, jint maxPacket, jint packetsPerSecond, jint fbEp,
        jint busIntervalsPerPacket, jint rate, jint channels, jint subslot, jint bits, jboolean inFloat) {
    auto* s = new Stream();
    s->fd = fd;
    s->ep = ep;
    s->maxPacket = maxPacket;
    s->packetsPerSecond = packetsPerSecond;
    s->fbEp = fbEp;
    s->busIntervalsPerPacket = busIntervalsPerPacket;
    s->rate = rate;
    s->channels = channels;
    s->outBytes = subslot;
    s->outBits = bits;
    s->inFloat = inFloat;
    // About half a second of audio.
    s->ring = std::make_unique<Ring>((size_t) rate / 2 * s->frameBytes());
    LOGI("USB stream %d Hz, %d ch, %d-bit in %d bytes, ep 0x%x fb 0x%x", rate, channels, bits, subslot, ep, fbEp);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jlong JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_openAAudio(
        JNIEnv*, jclass, jint deviceId, jint rate, jint channels, jboolean inFloat) {
    // Best first: the song's own depth, then wider integer, then float. Android often refuses
    // float in exclusive mode, so float is the last try, never the only one.
    struct Try { aaudio_format_t format; int bytes; int bits; bool isFloat; };
    std::vector<Try> tries;
    const bool wide = android_get_device_api_level() >= 31;
    if (!inFloat) tries.push_back({AAUDIO_FORMAT_PCM_I16, 2, 16, false});
    if (wide) {
        tries.push_back({AAUDIO_FORMAT_PCM_I24_PACKED, 3, 24, false});
        tries.push_back({AAUDIO_FORMAT_PCM_I32, 4, 32, false});
    }
    tries.push_back({AAUDIO_FORMAT_PCM_FLOAT, 4, 32, true});
    for (const Try& t : tries) {
        AAudioStreamBuilder* builder = nullptr;
        if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return 0;
        auto* s = new Stream();
        s->rate = rate;
        s->channels = channels;
        s->inFloat = inFloat;
        s->outFloat = t.isFloat;
        s->outBytes = t.bytes;
        s->outBits = t.bits;
        AAudioStreamBuilder_setDeviceId(builder, deviceId);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSampleRate(builder, rate);
        AAudioStreamBuilder_setChannelCount(builder, channels);
        AAudioStreamBuilder_setFormat(builder, t.format);
        if (__builtin_available(android 28, *)) {
            AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
            AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        }
        AAudioStreamBuilder_setDataCallback(builder, aaudioCallback, s);
        AAudioStream* stream = nullptr;
        aaudio_result_t result = AAudioStreamBuilder_openStream(builder, &stream);
        AAudioStreamBuilder_delete(builder);
        if (result != AAUDIO_OK || stream == nullptr) {
            LOGI("AAudio format %d refused: %s", (int) t.format, AAudio_convertResultToText(result));
            delete s;
            continue;
        }
        // Exclusive, at the song's own rate and this exact format, on the asked device; else next.
        if (AAudioStream_getSharingMode(stream) != AAUDIO_SHARING_MODE_EXCLUSIVE ||
            AAudioStream_getSampleRate(stream) != rate ||
            AAudioStream_getChannelCount(stream) != channels ||
            AAudioStream_getFormat(stream) != t.format ||
            (deviceId != 0 && AAudioStream_getDeviceId(stream) != deviceId)) {
            LOGI("AAudio format %d not exclusive at %d Hz", (int) t.format, rate);
            AAudioStream_close(stream);
            delete s;
            continue;
        }
        s->aaudio = stream;
        s->ring = std::make_unique<Ring>((size_t) rate / 2 * s->frameBytes());
        LOGI("AAudio exclusive %d Hz, format %d, device %d", rate, (int) t.format, AAudioStream_getDeviceId(stream));
        return reinterpret_cast<jlong>(s);
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_outputBits(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    return s->outFloat ? 0 : s->outBits;  // 0 = float
}

JNIEXPORT jboolean JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_isExact(JNIEnv*, jclass, jlong h) {
    return fromHandle(h)->exact.load();
}

// While the stream is stopped: hands back what was queued but never played, in output format,
// so it can be put on a reopened stream unchanged.
JNIEXPORT jbyteArray JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_takeQueued(JNIEnv* env, jclass, jlong h) {
    Stream* s = fromHandle(h);
    size_t n = s->ring->readable();
    jbyteArray out = env->NewByteArray((jsize) n);
    if (!out || n == 0) return out;
    std::vector<uint8_t> tmp(n);
    n = s->ring->read(tmp.data(), n);
    env->SetByteArrayRegion(out, 0, (jsize) n, (const jbyte*) tmp.data());
    return out;
}

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_putQueued(JNIEnv* env, jclass, jlong h, jbyteArray data) {
    Stream* s = fromHandle(h);
    jsize n = env->GetArrayLength(data);
    size_t fit = std::min((size_t) n, s->ring->writable()) / s->frameBytes() * s->frameBytes();
    if (fit == 0) return 0;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    s->ring->write((const uint8_t*) bytes, fit);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return (jint) fit;
}

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_writeDirect(
        JNIEnv* env, jclass, jlong h, jobject buffer, jint offset, jint length) {
    auto* data = (uint8_t*) env->GetDirectBufferAddress(buffer);
    if (!data) return 0;
    return (jint) pushFrames(fromHandle(h), data + offset, (size_t) length);
}

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_writeArray(
        JNIEnv* env, jclass, jlong h, jbyteArray array, jint offset, jint length) {
    jbyte* data = env->GetByteArrayElements(array, nullptr);
    jint used = (jint) pushFrames(fromHandle(h), (uint8_t*) data + offset, (size_t) length);
    env->ReleaseByteArrayElements(array, data, JNI_ABORT);
    return used;
}

JNIEXPORT void JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_start(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    if (s->aaudio) AAudioStream_requestStart(s->aaudio);
    else usbStart(s);
}

JNIEXPORT void JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_pause(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    if (s->aaudio) AAudioStream_requestPause(s->aaudio);
    else usbStop(s);
}

JNIEXPORT void JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_flush(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    s->ring->clear();
    s->generation.fetch_add(1);
    s->framesPlayed.store(0);
    s->exact.store(true);
}

JNIEXPORT jlong JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_framesPlayed(JNIEnv*, jclass, jlong h) {
    return fromHandle(h)->framesPlayed.load();
}

JNIEXPORT jint JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_queuedFrames(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    return (jint) (s->ring->readable() / s->frameBytes());
}

JNIEXPORT void JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_setGain(JNIEnv*, jclass, jlong h, jfloat gain) {
    fromHandle(h)->gain.store(gain);
}

JNIEXPORT void JNICALL
Java_com_abn3li_telemusic_playback_bitperfect_NativeBitPerfect_close(JNIEnv*, jclass, jlong h) {
    Stream* s = fromHandle(h);
    if (s->aaudio) {
        AAudioStream_requestStop(s->aaudio);
        AAudioStream_close(s->aaudio);
    } else {
        usbStop(s);
    }
    delete s;
}

}  // extern "C"
