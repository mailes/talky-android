#include "tgnet/jarvis/JarvisTicket.h"
#include <cassert>
int main() {
 unsigned char key[256];for(int i=0;i<256;++i)key[i]=static_cast<unsigned char>(i);
 assert(talky_jarvis::ticket(key,256,UINT64_MAX,7,2000000000)=="djE6MTg0NDY3NDQwNzM3MDk1NTE2MTU6NzoyMDAwMDAwMDAw.hhK9XjIq9xZ_sCs1DwOsYRSgCMi9jo4n6t4R2WjPKps");
 assert(talky_jarvis::ticket(nullptr,0,1,7,2000000000).empty());
}
