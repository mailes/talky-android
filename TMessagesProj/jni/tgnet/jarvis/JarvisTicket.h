#pragma once
#include <cstdint>
#include <string>
#include <openssl/hmac.h>

namespace talky_jarvis {
inline std::string base64url(const unsigned char *data, size_t length) {
    static const char alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    std::string out;
    uint32_t bits = 0;
    int count = 0;
    for (size_t i = 0; i < length; ++i) {
        bits = (bits << 8) | data[i]; count += 8;
        while (count >= 6) { count -= 6; out.push_back(alphabet[(bits >> count) & 63]); }
    }
    if (count) out.push_back(alphabet[(bits << (6 - count)) & 63]);
    return out;
}
inline std::string ticket(const unsigned char *key, size_t length, uint64_t keyId, int64_t userId, int64_t expires) {
    if (!key || length != 256 || userId <= 0 || expires <= 0) return {};
    const auto payload = std::string("v1:") + std::to_string(keyId) + ":" + std::to_string(userId) + ":" + std::to_string(expires);
    std::string input = "talky-jarvis-realtime-ticket-v1";
    input.push_back('\0'); input += payload;
    unsigned char mac[EVP_MAX_MD_SIZE]; unsigned int size = 0;
    if (!HMAC(EVP_sha256(), key, static_cast<int>(length), reinterpret_cast<const unsigned char *>(input.data()), input.size(), mac, &size)) return {};
    return base64url(reinterpret_cast<const unsigned char *>(payload.data()), payload.size()) + "." + base64url(mac, size);
}
}
