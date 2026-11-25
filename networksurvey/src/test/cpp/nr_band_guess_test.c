#include "../../main/cpp/nr_band_guess.h"
#include <stdio.h>

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); return 1; } } while (0)

int main(void) {
    CHECK(nr_band_guess(627264) == 0);   /* n77 and n78 */
    CHECK(nr_band_guess(620000) == 0);
    CHECK(nr_band_guess(653333) == 0);
    CHECK(nr_band_guess(636667) == 0);   /* n48 inside n77/n78 */
    CHECK(nr_band_guess(653334) == 77);  /* n77 above n78 */
    CHECK(nr_band_guess(680000) == 77);
    CHECK(nr_band_guess(499200) == 41);  /* n41 below n38 */
    CHECK(nr_band_guess(513999) == 41);
    CHECK(nr_band_guess(514000) == 0);   /* n38 and n41 */
    CHECK(nr_band_guess(524000) == 0);   /* n38, n7 and n41 */
    CHECK(nr_band_guess(537999) == 0);   /* n7 and n41 */
    CHECK(nr_band_guess(633333) == 0);
    CHECK(nr_band_guess(422000) == 1);
    CHECK(nr_band_guess(700000) == 79);
    CHECK(nr_band_guess(0) == 0);
    puts("nr_band_guess_test ok");
    return 0;
}
