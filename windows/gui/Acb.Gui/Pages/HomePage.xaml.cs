using Acb.Gui.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Acb.Gui.Pages;

public sealed partial class HomePage : Page
{
    private readonly ReceiverApiClient _client = new();

    public HomePage()
    {
        InitializeComponent();
    }

    private async void OnRefresh(object sender, RoutedEventArgs e)
    {
        OutputBox.Text = await _client.GetDevicesAsync();
    }
}
