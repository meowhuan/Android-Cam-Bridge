#pragma once
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdint>
#include <cstring>

#pragma pack(push, 1)
struct SharedFrameHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint64_t pts;
    uint32_t slotCount;
    uint32_t slotSize;
    uint32_t frameSize;
    uint32_t frameIndex;
};
#pragma pack(pop)

class SharedFrameReader {
public:
    SharedFrameReader() = default;

    ~SharedFrameReader() {
        Close();
    }

    bool Open() {
        if (mapView_) return true;
        hMap_ = OpenFileMappingW(FILE_MAP_READ, FALSE, kMapName);
        if (!hMap_) return false;
        mapView_ = static_cast<uint8_t*>(
            MapViewOfFile(hMap_, FILE_MAP_READ, 0, 0, kTotalSize));
        if (!mapView_) {
            CloseHandle(hMap_);
            hMap_ = nullptr;
            return false;
        }
        return true;
    }

    void Close() {
        if (mapView_) {
            UnmapViewOfFile(mapView_);
            mapView_ = nullptr;
        }
        if (hMap_) {
            CloseHandle(hMap_);
            hMap_ = nullptr;
        }
    }

    bool IsOpen() const { return mapView_ != nullptr; }

    // Read a frame with torn-read protection.
    // Returns true if a new frame was read successfully.
    // dst must have at least dstSize bytes. width/height are output params.
    bool ReadFrame(uint8_t* dst, uint32_t dstSize, uint32_t& width, uint32_t& height) {
        if (!mapView_) {
            if (!Open()) return false;
        }

        static_assert(sizeof(SharedFrameHeader) == 44, "SharedFrameHeader size mismatch");

        // Retry loop for torn-read protection
        for (int attempt = 0; attempt < 3; ++attempt) {
            // Read header snapshot 1
            SharedFrameHeader hdr1;
            std::memcpy(&hdr1, mapView_, kHeaderSize);

            // Validate
            if (hdr1.magic != kMagic || hdr1.version < 2)
                return false;
            if (hdr1.width == 0 || hdr1.height == 0)
                return false;
            if (hdr1.slotCount == 0 || hdr1.slotCount > kSlotCount)
                return false;
            if (hdr1.slotSize == 0 || hdr1.slotSize > kSlotSize)
                return false;
            if (hdr1.frameSize == 0 || hdr1.frameSize > hdr1.slotSize)
                return false;
            if (hdr1.frameIndex >= hdr1.slotCount)
                return false;
            if (hdr1.frameSize > dstSize)
                return false;

            // Check if this is the same frame we already have
            if (hdr1.frameIndex == lastFrameIndex_ && hdr1.pts == lastPts_) {
                width = hdr1.width;
                height = hdr1.height;
                return false; // No new frame
            }

            // Copy the frame data
            const size_t offset = kHeaderSize + static_cast<size_t>(hdr1.frameIndex) * hdr1.slotSize;
            std::memcpy(dst, mapView_ + offset, hdr1.frameSize);

            // Read header snapshot 2 for torn-read detection
            SharedFrameHeader hdr2;
            std::memcpy(&hdr2, mapView_, kHeaderSize);

            // If frameIndex or pts changed during our copy, the frame may be torn
            if (hdr2.frameIndex == hdr1.frameIndex && hdr2.pts == hdr1.pts) {
                // Consistent read
                lastFrameIndex_ = hdr1.frameIndex;
                lastPts_ = hdr1.pts;
                width = hdr1.width;
                height = hdr1.height;
                return true;
            }
            // else: torn read detected, retry
        }
        return false;
    }

    bool GetResolution(uint32_t& width, uint32_t& height) {
        if (!mapView_) {
            if (!Open()) return false;
        }
        SharedFrameHeader hdr;
        std::memcpy(&hdr, mapView_, kHeaderSize);
        if (hdr.magic != kMagic || hdr.width == 0 || hdr.height == 0)
            return false;
        width = hdr.width;
        height = hdr.height;
        return true;
    }

private:
    static constexpr const wchar_t* kMapName = L"Local\\acb_virtualcam_frame";
    static constexpr uint32_t kMagic = 0x42434141;
    static constexpr size_t kHeaderSize = sizeof(SharedFrameHeader);
    static constexpr size_t kSlotSize = 3840ULL * 2160 * 4;
    static constexpr size_t kSlotCount = 3;
    static constexpr size_t kTotalSize = kHeaderSize + kSlotCount * kSlotSize;

    HANDLE hMap_ = nullptr;
    uint8_t* mapView_ = nullptr;
    uint32_t lastFrameIndex_ = UINT32_MAX;
    uint64_t lastPts_ = 0;
};
