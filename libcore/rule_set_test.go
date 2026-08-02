package libcore

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/sagernet/sing-box/common/srs"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func TestWriteRuleSetBatch(t *testing.T) {
	outputDirectory := t.TempDir()
	err := writeRuleSetBatch(outputDirectory, "geosite", map[string][]option.HeadlessRule{
		"cn": {{
			Type: C.RuleTypeDefault,
			DefaultOptions: option.DefaultHeadlessRule{
				DomainSuffix: []string{"example.cn"},
			},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	outputFile, err := os.Open(filepath.Join(outputDirectory, "geosite-cn.srs"))
	if err != nil {
		t.Fatal(err)
	}
	defer outputFile.Close()
	ruleSet, err := srs.Read(outputFile, false)
	if err != nil {
		t.Fatal(err)
	}
	if ruleSet.Version != C.RuleSetVersion2 || len(ruleSet.Options.Rules) != 1 {
		t.Fatalf("unexpected converted rule-set: version=%d rules=%d", ruleSet.Version, len(ruleSet.Options.Rules))
	}
}

func TestParseRuleSetCodesRejectsPath(t *testing.T) {
	if _, err := parseRuleSetCodes("cn ../private"); err == nil {
		t.Fatal("expected invalid rule-set code error")
	}
}
