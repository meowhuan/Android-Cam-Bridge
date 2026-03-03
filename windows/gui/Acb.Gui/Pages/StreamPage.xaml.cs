using Acb.Gui.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Acb.Gui.Pages;

public sealed partial class StreamPage : Page
{
    private readonly ReceiverApiClient _client = new();

    public StreamPage()
    {
        InitializeComponent();
    }

    private async void OnStartSession(object sender, RoutedEventArgs e)
    {
        var transport = ((TransportBox.SelectedItem as ComboBoxItem)?.Content?.ToString()) ?? "usb-adb";
        SessionOutput.Text = await _client.StartV2SessionAsync(new StartSessionOptions { Transport = transport });
    }
}
