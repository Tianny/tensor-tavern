# Financial Analysis Skill

A progressive disclosure skill for financial analysis, demonstrating the three-layer architecture.

## Structure

```
03-financial-skill/
├── SKILL.md                    # Main skill file (always loaded)
├── reference/
│   ├── revenue.md             # Revenue analysis formulas
│   ├── costs.md               # Cost analysis formulas
│   └── profitability.md       # Profitability metrics
├── templates/
│   └── analysis_report.md     # Report template
└── scripts/
    └── calculate_ratios.py    # Calculation script
```

## Progressive Loading

| User Request | Files Loaded | Tokens |
|--------------|--------------|--------|
| "What's gross margin?" | SKILL.md + profitability.md | ~1500 |
| "Analyze revenue growth" | SKILL.md + revenue.md | ~1400 |
| "Full financial analysis" | All files | ~4000 |

## Usage

This skill is triggered when users ask about:
- Revenue, sales, growth rates
- Costs, expenses, efficiency
- Profits, margins, ROI
- Financial analysis or reports

## Script Usage

```bash
# Create a data file
echo '{"revenue": 1000000, "cogs": 400000, "net_income": 150000}' > data.json

# Run calculations
python scripts/calculate_ratios.py data.json
```
