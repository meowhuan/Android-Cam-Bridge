using Acb.Gui.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Acb.Gui.Pages;

public sealed partial class DiagnosticsPage : Page
{
    private readonly ReceiverApiClient _client = new();

    public DiagnosticsPage()
    {
        InitializeComponent();
    }

    private async void OnFetchStats(object sender, RoutedEventArgs e)
    {
        var id = SessionIdBox.Text;
        if (string.IsNullOrWhiteSpace(id))
        {
            StatsOutput.Text = "please input session id";
            return;
        }
        StatsOutput.Text = await _client.GetV2StatsAsync(id.Trim());
    }
}
