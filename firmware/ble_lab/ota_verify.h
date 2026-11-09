// Authenticity check for an OTA image: ECDSA P-256 over a manifest that binds size, version and
// the image SHA-256, verified against the public key compiled into the firmware.
// mbedTLS 3.6 (Arduino-ESP32 3.3.x): https://mbed-tls.readthedocs.io/ (ecdsa.h, ecp.h, sha256.h)
// The key is public: it proves who signed an image, which a bare SHA-256 sent by the phone cannot.
#pragma once
#include <mbedtls/ecdsa.h>
#include <mbedtls/ecp.h>
#include <mbedtls/sha256.h>
#include <stdint.h>
#include <string.h>
#include "ota_public_key.h"

namespace ota_verify {

static constexpr size_t SIGNATURE_LENGTH = 64;  // raw r || s, 32 bytes each, big-endian

// True when [signature] is a valid signature by OTA_PUBLIC_KEY over "BLFW1" | size LE | version LE | imageSha256.
static bool manifestSigned(uint32_t size, uint32_t versionCode, const uint8_t imageSha256[32], const uint8_t signature[SIGNATURE_LENGTH]) {
  uint8_t message[5 + 4 + 4 + 32];
  memcpy(message, "BLFW1", 5);
  for (int i = 0; i < 4; i++) {
    message[5 + i] = (size >> (8 * i)) & 0xFF;
    message[9 + i] = (versionCode >> (8 * i)) & 0xFF;
  }
  memcpy(message + 13, imageSha256, 32);
  uint8_t digest[32];
  if (mbedtls_sha256(message, sizeof(message), digest, 0) != 0) return false;

  mbedtls_ecp_group group;
  mbedtls_ecp_point q;
  mbedtls_mpi r, s;
  mbedtls_ecp_group_init(&group);
  mbedtls_ecp_point_init(&q);
  mbedtls_mpi_init(&r);
  mbedtls_mpi_init(&s);
  bool ok = mbedtls_ecp_group_load(&group, MBEDTLS_ECP_DP_SECP256R1) == 0 &&
            mbedtls_ecp_point_read_binary(&group, &q, OTA_PUBLIC_KEY, sizeof(OTA_PUBLIC_KEY)) == 0 &&
            mbedtls_ecp_check_pubkey(&group, &q) == 0 &&
            mbedtls_mpi_read_binary(&r, signature, 32) == 0 &&
            mbedtls_mpi_read_binary(&s, signature + 32, 32) == 0 &&
            mbedtls_ecdsa_verify(&group, digest, sizeof(digest), &q, &r, &s) == 0;
  mbedtls_mpi_free(&s);
  mbedtls_mpi_free(&r);
  mbedtls_ecp_point_free(&q);
  mbedtls_ecp_group_free(&group);
  return ok;
}

}  // namespace ota_verify
