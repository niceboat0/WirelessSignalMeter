#!/usr/bin/env bash
# Host tests for the DIAG decode queue and Samsung IMS logcat parser.
# Does not talk to a phone.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
cc="${CC:-cc}"
flags=(-std=c11 -Wall -Wextra -Werror -O2)
"$cc" "${flags[@]}" -o /tmp/diag_queue_test "$root/networksurvey/src/test/cpp/diag_queue_test.c"
/tmp/diag_queue_test
"$cc" "${flags[@]}" -o /tmp/ims_logcat_test "$root/networksurvey/src/test/cpp/ims_logcat_test.c"
/tmp/ims_logcat_test
"$cc" "${flags[@]}" -o /tmp/diag_session_file_test "$root/networksurvey/src/test/cpp/diag_session_file_test.c"
/tmp/diag_session_file_test
"$cc" "${flags[@]}" -o /tmp/lte_b193_sinr_test "$root/networksurvey/src/test/cpp/lte_b193_sinr_test.c"
/tmp/lte_b193_sinr_test
"$cc" "${flags[@]}" -o /tmp/nr_band_guess_test "$root/networksurvey/src/test/cpp/nr_band_guess_test.c"
/tmp/nr_band_guess_test
"$cc" "${flags[@]}" -o /tmp/nr_csf_window_test "$root/networksurvey/src/test/cpp/nr_csf_window_test.c"
/tmp/nr_csf_window_test
echo "host C tests passed"
