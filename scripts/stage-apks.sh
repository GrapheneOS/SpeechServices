#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT_DIR
cd "${ROOT_DIR}"

readonly GRADLE="${GRADLE:-${ROOT_DIR}/gradlew}"
readonly BUNDLETOOL="${BUNDLETOOL:-bundletool}"
readonly AAPT2="${AAPT2:-aapt2}"
readonly UNZIP="${UNZIP:-unzip}"
readonly KEYSTORE_PROPERTIES="${KEYSTORE_PROPERTIES:-${ROOT_DIR}/keystore.properties}"
readonly BUNDLE_FILE="${BUNDLE_FILE:-${ROOT_DIR}/app/build/outputs/bundle/release/app-release.aab}"
readonly APK_SET_OUTPUT="${APK_SET_OUTPUT:-${ROOT_DIR}/app/build/outputs/apk-set/release/SpeechServices.apks}"
readonly APPSTORE_APK_DIR="${APPSTORE_APK_DIR:-${ROOT_DIR}/app/build/outputs/apk-set/release/appstore}"

readonly BASE_APK="base-master.apk"
readonly ARM64_SPLIT_APK="base-arm64_v8a.apk"
readonly X86_64_SPLIT_APK="base-x86_64.apk"

store_password_fd=
key_password_fd=
tmp_dir=

die() {
    printf '%s\n' "$1" >&2
    exit 1
}

cleanup() {
    if [[ -n "${store_password_fd}" ]]; then
        exec {store_password_fd}<&-
    fi
    if [[ -n "${key_password_fd}" ]]; then
        exec {key_password_fd}<&-
    fi
    if [[ -n "${tmp_dir}" ]]; then
        rm -rf -- "${tmp_dir}"
    fi
}
trap cleanup EXIT

require_executable() {
    local env_name="$1"
    local executable="$2"

    if ! command -v -- "${executable}" >/dev/null 2>&1; then
        die "Missing executable: ${executable}. Set ${env_name} or add it to PATH."
    fi
}

read_property() {
    local key="$1"
    local env_name="$2"
    local matching_lines
    local line
    local value

    if [[ ! -f "${KEYSTORE_PROPERTIES}" ]]; then
        return 0
    fi

    matching_lines="$(
        sed -n -E "/^[[:space:]]*${key}[[:space:]]*[:=]/p" "${KEYSTORE_PROPERTIES}"
    )"
    if [[ -z "${matching_lines}" ]]; then
        return 0
    fi

    line="$(printf '%s\n' "${matching_lines}" | tail -n 1)"
    if [[ ! "${line}" =~ ^[[:space:]]*${key}[[:space:]]*= ]]; then
        die "Unsupported ${key} syntax in ${KEYSTORE_PROPERTIES}. Use an unescaped ${key}=value line or set ${env_name}."
    fi
    if [[ "${line}" == *\\* ]]; then
        die "Unsupported escaped ${key} value in ${KEYSTORE_PROPERTIES}. Use an unescaped ${key}=value line or set ${env_name}."
    fi

    value="$(
        printf '%s\n' "${line}" |
            sed -n -E "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\\1/p"
    )"
    printf '%s' "${value}"
}

required_value() {
    local env_name="$1"
    local property_name="$2"
    local value="${!env_name:-}"

    if [[ -z "${value}" ]]; then
        value="$(read_property "${property_name}" "${env_name}")"
    fi

    if [[ -z "${value}" ]]; then
        die "Missing ${property_name}. Set ${env_name} or add ${property_name} to ${KEYSTORE_PROPERTIES}."
    fi

    printf '%s' "${value}"
}

validate_apk_set() {
    local expected_entries="${tmp_dir}/expected-apk-set-entries"
    local actual_entries="${tmp_dir}/actual-apk-set-entries"

    printf '%s\n' \
        "splits/${ARM64_SPLIT_APK}" \
        "splits/${BASE_APK}" \
        "splits/${X86_64_SPLIT_APK}" \
        "toc.pb" |
        LC_ALL=C sort >"${expected_entries}"
    "${UNZIP}" -Z -1 "${APK_SET_OUTPUT}" | LC_ALL=C sort >"${actual_entries}"

    if ! cmp -s "${expected_entries}" "${actual_entries}"; then
        printf 'Unexpected APK set contents in %s.\n' "${APK_SET_OUTPUT}" >&2
        printf 'Expected:\n' >&2
        sed 's/^/  /' "${expected_entries}" >&2
        printf 'Actual:\n' >&2
        sed 's/^/  /' "${actual_entries}" >&2
        exit 1
    fi
}

stage_apk() {
    local source_apk="$1"
    local output_name="$2"
    local output_apk="${APPSTORE_APK_DIR}/${output_name}"

    rm -f -- "${output_apk}"
    cp -- "${source_apk}" "${output_apk}"
}

prepare_appstore_output_dir() {
    local staged_apks=(
        "${APPSTORE_APK_DIR}/base-master.apk"
        "${APPSTORE_APK_DIR}"/base.config.*.apk
    )

    mkdir -p "${APPSTORE_APK_DIR}"
    rm -f -- "${staged_apks[@]}"
}

require_executable GRADLE "${GRADLE}"
require_executable BUNDLETOOL "${BUNDLETOOL}"
require_executable AAPT2 "${AAPT2}"
require_executable UNZIP "${UNZIP}"

store_file="$(required_value STORE_FILE storeFile)"
store_password="$(required_value STORE_PASSWORD storePassword)"
key_alias="$(required_value KEY_ALIAS keyAlias)"
key_password="$(required_value KEY_PASSWORD keyPassword)"

if [[ "${store_file}" != /* ]]; then
    store_file="${ROOT_DIR}/${store_file}"
fi
if [[ ! -f "${store_file}" ]]; then
    die "Keystore file does not exist: ${store_file}"
fi

"${GRADLE}" :app:bundleRelease

mkdir -p "$(dirname "${APK_SET_OUTPUT}")"
rm -f -- "${APK_SET_OUTPUT}"

exec {store_password_fd}< <(printf '%s\n' "${store_password}")
exec {key_password_fd}< <(printf '%s\n' "${key_password}")

"${BUNDLETOOL}" build-apks \
    --bundle="${BUNDLE_FILE}" \
    --output="${APK_SET_OUTPUT}" \
    --overwrite \
    --aapt2="${AAPT2}" \
    --ks="${store_file}" \
    --ks-pass="file:/proc/self/fd/${store_password_fd}" \
    --ks-key-alias="${key_alias}" \
    --key-pass="file:/proc/self/fd/${key_password_fd}"

tmp_dir="$(mktemp -d)"
validate_apk_set
"${UNZIP}" -q "${APK_SET_OUTPUT}" \
    "splits/${BASE_APK}" \
    "splits/${ARM64_SPLIT_APK}" \
    "splits/${X86_64_SPLIT_APK}" \
    -d "${tmp_dir}"

for split in ${BASE_APK} ${ARM64_SPLIT_APK} ${X86_64_SPLIT_APK}; do
    exec {store_password_fd}< <(printf '%s\n' "${store_password}")
    exec {key_password_fd}< <(printf '%s\n' "${key_password}")
    apksigner sign --v4-signing-enabled true \
        --ks="${store_file}" \
        --ks-pass="file:/proc/self/fd/${store_password_fd}" \
        --ks-key-alias="${key_alias}" \
        --key-pass="file:/proc/self/fd/${key_password_fd}" \
        "${tmp_dir}/splits/${split}"
done

prepare_appstore_output_dir

stage_apk "${tmp_dir}/splits/${BASE_APK}" "base-master.apk"
stage_apk "${tmp_dir}/splits/${BASE_APK}" "base-master.apk.idsig"
stage_apk "${tmp_dir}/splits/${ARM64_SPLIT_APK}" "base.config.arm64_v8a.apk"
stage_apk "${tmp_dir}/splits/${ARM64_SPLIT_APK}" "base.config.arm64_v8a.apk.idsig"
stage_apk "${tmp_dir}/splits/${X86_64_SPLIT_APK}" "base.config.x86_64.apk"
stage_apk "${tmp_dir}/splits/${X86_64_SPLIT_APK}" "base.config.x86_64.apk.idsig"

printf 'Built AOSP APK set: %s\n' "${APK_SET_OUTPUT}"
printf 'Built AppStore APKs: %s\n' "${APPSTORE_APK_DIR}"
