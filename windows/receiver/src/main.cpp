#include "receiver_server.h"

#include <chrono>
#include <iostream>
#include <thread>

int main() {
  using namespace acb::receiver;

  ReceiverServer server(39393);
  if (!server.Start()) {
    std::cerr << "failed to start receiver server on 39393\n";
    return 1;
  }

  std::cout << "acb-receiver started at http://127.0.0.1:39393\n";
  std::cout << "press Ctrl+C to stop\n";

  while (true) {
    std::this_thread::sleep_for(std::chrono::seconds(1));
  }

  return 0;
}
