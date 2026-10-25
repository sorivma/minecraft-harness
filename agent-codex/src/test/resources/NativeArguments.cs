using System;
using System.Text;

// Windows-native argv fixture: Java launchers can narrow Unicode via the ANSI code page.
class NativeArguments {
    static void Main(string[] args) {
        Console.ReadLine();
        var result = new StringBuilder("{\"id\":1,\"result\":{\"arguments\":[");
        for (int i = 0; i < args.Length; i++) {
            if (i > 0) result.Append(',');
            result.Append('"');
            foreach (char c in args[i]) result.Append("\\u").Append(((int)c).ToString("x4"));
            result.Append('"');
        }
        Console.WriteLine(result.Append("]}}"));
    }
}
